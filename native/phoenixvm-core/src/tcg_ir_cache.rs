use sha2::{Digest, Sha256};
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, SystemTime};

#[cfg(any(target_os = "linux", target_os = "android"))]
extern "C" {
    fn flock(fd: i32, operation: i32) -> i32;
}

/// Kernel-managed advisory lock shared by every PhoenixVM process using this L2 root.
///
/// The lock file is permanent: unlinking it while another process holds an open file
/// descriptor would allow a third process to lock a different inode and bypass mutual
/// exclusion. flock is released by the kernel when the owning process exits.
struct ProcessFileLock {
    _file: File,
}

impl ProcessFileLock {
    fn acquire(root: &Path) -> Result<Self, TcgIrCacheError> {
        #[cfg(any(target_os = "linux", target_os = "android"))]
        {
            use std::os::fd::AsRawFd;
            use std::os::unix::fs::OpenOptionsExt;

            // Linux and Android both define O_NOFOLLOW as 0x20000. This prevents
            // an attacker-controlled symlink from redirecting the lock-file open.
            const O_NOFOLLOW: i32 = 0x20000;
            const LOCK_EX: i32 = 2;

            let path = root.join(".tcg-ir-l2.lock");
            let file = OpenOptions::new()
                .read(true)
                .write(true)
                .create(true)
                .mode(0o600)
                .custom_flags(O_NOFOLLOW)
                .open(path)
                .map_err(|error| TcgIrCacheError::Io(error.kind()))?;
            if !file
                .metadata()
                .map_err(|error| TcgIrCacheError::Io(error.kind()))?
                .is_file()
            {
                return Err(TcgIrCacheError::Invalid(
                    "L2 process lock must be a regular file",
                ));
            }

            loop {
                // SAFETY: the descriptor remains owned by file until the guard
                // drops; flock does not retain or dereference caller memory.
                if unsafe { flock(file.as_raw_fd(), LOCK_EX) } == 0 {
                    return Ok(Self { _file: file });
                }
                let error = std::io::Error::last_os_error();
                if error.kind() != std::io::ErrorKind::Interrupted {
                    return Err(TcgIrCacheError::Io(error.kind()));
                }
            }
        }

        #[cfg(not(any(target_os = "linux", target_os = "android")))]
        {
            let _ = root;
            Err(TcgIrCacheError::Invalid(
                "cross-process L2 locking is supported only on Linux and Android",
            ))
        }
    }
}

impl Drop for ProcessFileLock {
    fn drop(&mut self) {
        #[cfg(any(target_os = "linux", target_os = "android"))]
        {
            use std::os::fd::AsRawFd;
            const LOCK_UN: i32 = 8;
            // SAFETY: the descriptor is valid for the lifetime of this guard.
            let _ = unsafe { flock(self._file.as_raw_fd(), LOCK_UN) };
        }
    }
}

const MAGIC: &[u8; 8] = b"PVTCGIR\0";
const FORMAT_VERSION: u16 = 3;
const HEADER_BYTES: usize = 14;
const DIGEST_BYTES: usize = 32;
const MAX_CACHE_BYTES: usize = 2 * 1024 * 1024;
const MAX_STRING_BYTES: usize = 256;
const MAX_CPU_FEATURES: usize = 128;
const MAX_TEMPS: usize = 512;
const MAX_LABELS: usize = 512;
const MAX_OPERATIONS: usize = 16_384;
const MAX_OPERANDS: usize = 32;
const MAX_INSTRUCTIONS: u16 = 512;
static TEMP_FILE_SEQUENCE: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TcgEndian {
    Little,
    Big,
}

impl TcgEndian {
    fn encode(self) -> u8 {
        match self {
            Self::Little => 0,
            Self::Big => 1,
        }
    }

    fn decode(value: u8) -> Result<Self, TcgIrCacheError> {
        match value {
            0 => Ok(Self::Little),
            1 => Ok(Self::Big),
            _ => Err(TcgIrCacheError::Corrupt),
        }
    }
}

/// Complete compatibility identity for one translated guest block.
///
/// The caller must re-read and hash the current guest code before accepting a
/// cache hit. This structure deliberately contains no process addresses.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcgIrCompatibility {
    pub qemu_build_id: String,
    pub guest_arch: String,
    pub guest_cpu_model: String,
    pub guest_build_id: String,
    pub host_arch: String,
    pub host_abi: String,
    pub host_endian: TcgEndian,
    pub host_page_size: u32,
    /// Sorted, unique, canonical host CPU feature names.
    pub host_cpu_features: Vec<String>,
    pub guest_pc: u64,
    pub guest_cs_base: u64,
    pub tb_flags: u64,
    pub tb_cflags: u64,
    pub mmu_index: u32,
    /// Physical code pages covered by this TB (one or two in QEMU 11.1.3).
    pub physical_pages: Vec<u64>,
    pub guest_code_sha256: [u8; 32],
    /// Digest of all target-specific translation context not represented above.
    pub tb_context_sha256: [u8; 32],
}

impl TcgIrCompatibility {
    pub fn validate(&self) -> Result<(), TcgIrCacheError> {
        for value in [
            &self.qemu_build_id,
            &self.guest_arch,
            &self.guest_cpu_model,
            &self.guest_build_id,
            &self.host_arch,
            &self.host_abi,
        ] {
            validate_identifier(value, MAX_STRING_BYTES)?;
        }

        if self.host_page_size < 512 || !self.host_page_size.is_power_of_two() {
            return Err(TcgIrCacheError::Invalid("invalid host page size"));
        }
        if self.host_cpu_features.len() > MAX_CPU_FEATURES {
            return Err(TcgIrCacheError::Invalid("too many host CPU features"));
        }
        let mut previous: Option<&str> = None;
        for feature in &self.host_cpu_features {
            validate_identifier(feature, MAX_STRING_BYTES)?;
            if previous.is_some_and(|value| value >= feature.as_str()) {
                return Err(TcgIrCacheError::Invalid(
                    "host CPU features must be sorted and unique",
                ));
            }
            previous = Some(feature);
        }
        if self.physical_pages.is_empty() || self.physical_pages.len() > 2 {
            return Err(TcgIrCacheError::Invalid("invalid physical page count"));
        }
        Ok(())
    }

    /// SHA-256 of the canonical compatibility identity, suitable for an L2 key.
    pub fn fingerprint(&self) -> Result<[u8; 32], TcgIrCacheError> {
        self.validate()?;
        let mut writer = Writer::default();
        write_compatibility(&mut writer, self)?;
        Ok(Sha256::digest(writer.bytes).into())
    }

    pub fn exactly_matches(&self, candidate: &Self) -> bool {
        self == candidate
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum TcgValueType {
    I32 = 0,
    I64 = 1,
    I128 = 2,
    V64 = 3,
    V128 = 4,
    V256 = 5,
    Ptr = 6,
}

impl TcgValueType {
    fn decode(value: u8) -> Result<Self, TcgIrCacheError> {
        match value {
            0 => Ok(Self::I32),
            1 => Ok(Self::I64),
            2 => Ok(Self::I128),
            3 => Ok(Self::V64),
            4 => Ok(Self::V128),
            5 => Ok(Self::V256),
            6 => Ok(Self::Ptr),
            _ => Err(TcgIrCacheError::Corrupt),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum TcgTempKind {
    Tb = 0,
    Ebb = 1,
}

impl TcgTempKind {
    fn decode(value: u8) -> Result<Self, TcgIrCacheError> {
        match value {
            0 => Ok(Self::Tb),
            1 => Ok(Self::Ebb),
            _ => Err(TcgIrCacheError::Corrupt),
        }
    }
}

/// A pointer-free description of one temporary allocated during translation.
///
/// \`base_id\` and \`subindex\` preserve multi-slot TCG temporary groups such as
/// I128 values; \`id\` remains the per-process-independent operand identifier.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcgTempRecord {
    pub id: u16,
    pub base_id: u16,
    pub subindex: u8,
    pub base_type: TcgValueType,
    pub value_type: TcgValueType,
    pub kind: TcgTempKind,
}

/// Operands are symbolic. The format has intentionally no raw-pointer variant.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TcgOperand {
    Temp(u16),
    /// Index in TCGContext.temps; tb_context_sha256 must cover the complete
    /// canonical layout of every global/fixed temp in the current QEMU context.
    Global(u16),
    Immediate(i64),
    Label(u16),
    /// Stable QEMU helper symbol, resolved by the current process on import.
    Helper(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcgIrOperation {
    /// Stable opcode spelling, not the build-local numeric TCGOpcode value.
    pub opcode: String,
    /// Raw TCGOp fields; meanings are opcode-specific (type, call arity, vector size, etc.).
    pub param1: u8,
    pub param2: u8,
    pub operands: Vec<TcgOperand>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcgLabelRecord {
    pub id: u16,
    /// Index into the operation stream where this label is defined.
    pub operation_index: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcgIrBlock {
    pub compatibility: TcgIrCompatibility,
    pub guest_size: u32,
    pub instruction_count: u16,
    pub temporaries: Vec<TcgTempRecord>,
    pub labels: Vec<TcgLabelRecord>,
    pub operations: Vec<TcgIrOperation>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TcgIrCacheError {
    Invalid(&'static str),
    Corrupt,
    UnsupportedVersion,
    TooLarge,
    Io(std::io::ErrorKind),
}

impl fmt::Display for TcgIrCacheError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Invalid(reason) => write!(f, "invalid TCG IR cache block: {reason}"),
            Self::Corrupt => write!(f, "corrupt TCG IR cache block"),
            Self::UnsupportedVersion => write!(f, "unsupported TCG IR cache format version"),
            Self::TooLarge => write!(f, "TCG IR cache block exceeds its size limit"),
            Self::Io(kind) => write!(f, "TCG IR cache I/O error: {kind}"),
        }
    }
}

impl std::error::Error for TcgIrCacheError {}

fn is_value_operand(operand: &TcgOperand) -> bool {
    matches!(
        operand,
        TcgOperand::Temp(_) | TcgOperand::Global(_) | TcgOperand::Immediate(_)
    )
}

fn is_writable_operand(operand: &TcgOperand) -> bool {
    matches!(operand, TcgOperand::Temp(_) | TcgOperand::Global(_))
}

fn validate_operation_type(operation: &TcgIrOperation) -> Result<TcgValueType, TcgIrCacheError> {
    if operation.param2 != 0 {
        return Err(TcgIrCacheError::Invalid(
            "unsupported TCG opcode parameter flags",
        ));
    }
    match operation.param1 {
        value if value == TcgValueType::I32 as u8 => Ok(TcgValueType::I32),
        value if value == TcgValueType::I64 as u8 => Ok(TcgValueType::I64),
        _ => Err(TcgIrCacheError::Invalid(
            "first TCG IR importer supports only I32/I64 scalar operations",
        )),
    }
}

fn validate_value_type(
    operand: &TcgOperand,
    expected: TcgValueType,
    temporaries: &[TcgTempRecord],
) -> Result<(), TcgIrCacheError> {
    if let TcgOperand::Temp(id) = operand {
        let temp = temporaries
            .get(usize::from(*id))
            .ok_or(TcgIrCacheError::Invalid("unknown temporary"))?;
        if temp.base_type != expected || temp.value_type != expected {
            return Err(TcgIrCacheError::Invalid(
                "temporary type does not match its TCG opcode",
            ));
        }
    }
    Ok(())
}

/// Closed, deliberately conservative TCG opcode schema.
///
/// This is a format-level gate, not yet the QEMU C importer. Every opcode not
/// listed here is rejected instead of being guessed from TCGOpDef argument
/// counts. Constants are accepted only where their meaning is unambiguous.
fn validate_operation_schema(
    operation: &TcgIrOperation,
    temporaries: &[TcgTempRecord],
) -> Result<(), TcgIrCacheError> {
    let operands = operation.operands.as_slice();
    let invalid = || TcgIrCacheError::Invalid("unsupported TCG opcode/operand schema");

    match operation.opcode.as_str() {
        "insn_start" => {
            if operation.param1 != 0
                || operation.param2 != 0
                || operands.len() != 3
                || !operands
                    .iter()
                    .all(|operand| matches!(operand, TcgOperand::Immediate(_)))
            {
                return Err(TcgIrCacheError::Invalid(
                    "insn_start must preserve exactly three immediate data words",
                ));
            }
        }
        "br" | "set_label" => {
            if operation.param1 != 0
                || operation.param2 != 0
                || operands.len() != 1
                || !matches!(operands[0], TcgOperand::Label(_))
            {
                return Err(invalid());
            }
        }
        "goto_tb" => {
            if operation.param1 != 0
                || operation.param2 != 0
                || !matches!(operands, [TcgOperand::Immediate(index)] if (0..=1).contains(index))
            {
                return Err(invalid());
            }
        }
        "exit_tb" => {
            // In PVTCGIR, this immediate is an abstract exit index, never the
            // old process's tcg_splitwx_to_rx(TB) address.
            if operation.param1 != 0
                || operation.param2 != 0
                || !matches!(operands, [TcgOperand::Immediate(index)] if matches!(*index, 0 | 1 | 3))
            {
                return Err(invalid());
            }
        }
        "mov" => {
            let ty = validate_operation_type(operation)?;
            if operands.len() != 2
                || !is_writable_operand(&operands[0])
                || !is_value_operand(&operands[1])
            {
                return Err(invalid());
            }
            validate_value_type(&operands[0], ty, temporaries)?;
            validate_value_type(&operands[1], ty, temporaries)?;
        }
        "add" | "sub" | "and" | "or" | "xor" | "mul" => {
            let ty = validate_operation_type(operation)?;
            if operands.len() != 3
                || !is_writable_operand(&operands[0])
                || !is_value_operand(&operands[1])
                || !is_value_operand(&operands[2])
            {
                return Err(invalid());
            }
            for operand in operands {
                validate_value_type(operand, ty, temporaries)?;
            }
        }
        _ => return Err(invalid()),
    }
    Ok(())
}

impl TcgIrBlock {
    pub fn validate(&self) -> Result<(), TcgIrCacheError> {
        self.compatibility.validate()?;
        if self.guest_size == 0 {
            return Err(TcgIrCacheError::Invalid("guest block size is zero"));
        }
        if self.instruction_count == 0 || self.instruction_count > MAX_INSTRUCTIONS {
            return Err(TcgIrCacheError::Invalid("invalid instruction count"));
        }
        if self.temporaries.len() > MAX_TEMPS {
            return Err(TcgIrCacheError::Invalid("too many temporaries"));
        }
        if self.labels.len() > MAX_LABELS {
            return Err(TcgIrCacheError::Invalid("too many labels"));
        }
        if self.operations.is_empty() || self.operations.len() > MAX_OPERATIONS {
            return Err(TcgIrCacheError::Invalid("invalid operation count"));
        }

        for (index, temp) in self.temporaries.iter().enumerate() {
            if usize::from(temp.id) != index {
                return Err(TcgIrCacheError::Invalid(
                    "temporary identifiers must be contiguous",
                ));
            }
            if usize::from(temp.base_id) >= self.temporaries.len()
                || temp.subindex > 3
                || (temp.subindex == 0 && temp.base_id != temp.id)
                || (temp.subindex != 0 && temp.base_id >= temp.id)
            {
                return Err(TcgIrCacheError::Invalid("invalid temporary group"));
            }
            if temp.subindex != 0 {
                let base = &self.temporaries[usize::from(temp.base_id)];
                if base.subindex != 0
                    || base.base_id != base.id
                    || base.base_type != temp.base_type
                    || base.kind != temp.kind
                    || u32::from(temp.id) != u32::from(temp.base_id) + u32::from(temp.subindex)
                {
                    return Err(TcgIrCacheError::Invalid(
                        "inconsistent multi-slot temporary group",
                    ));
                }
            }
        }

        let insn_start_count = self
            .operations
            .iter()
            .filter(|operation| operation.opcode == "insn_start")
            .count();
        if insn_start_count != usize::from(self.instruction_count) {
            return Err(TcgIrCacheError::Invalid(
                "instruction count does not match insn_start markers",
            ));
        }

        let label_definition_count = self
            .operations
            .iter()
            .filter(|operation| operation.opcode == "set_label")
            .count();
        if label_definition_count != self.labels.len() {
            return Err(TcgIrCacheError::Invalid(
                "label table and set_label operations do not match",
            ));
        }

        for (index, label) in self.labels.iter().enumerate() {
            let operation_index = label.operation_index as usize;
            if usize::from(label.id) != index || operation_index >= self.operations.len() {
                return Err(TcgIrCacheError::Invalid("invalid label table"));
            }
            let definition = &self.operations[operation_index];
            if definition.opcode != "set_label"
                || definition.operands.as_slice() != [TcgOperand::Label(label.id)]
            {
                return Err(TcgIrCacheError::Invalid(
                    "label definition does not match its set_label operation",
                ));
            }
        }

        for (operation_index, operation) in self.operations.iter().enumerate() {
            if operation.opcode == "set_label" {
                let Some(TcgOperand::Label(label_id)) = operation.operands.first() else {
                    return Err(TcgIrCacheError::Invalid(
                        "set_label operation has no label reference",
                    ));
                };
                if operation.operands.len() != 1
                    || self
                        .labels
                        .get(usize::from(*label_id))
                        .is_none_or(|label| label.operation_index as usize != operation_index)
                {
                    return Err(TcgIrCacheError::Invalid(
                        "set_label operation has no matching label table entry",
                    ));
                }
            }
        }

        for operation in &self.operations {
            validate_identifier(&operation.opcode, 64)?;
            if operation.operands.len() > MAX_OPERANDS {
                return Err(TcgIrCacheError::Invalid("too many operation operands"));
            }
            for operand in &operation.operands {
                match operand {
                    TcgOperand::Temp(id) if usize::from(*id) >= self.temporaries.len() => {
                        return Err(TcgIrCacheError::Invalid(
                            "operation references an unknown temporary",
                        ));
                    }
                    TcgOperand::Label(id) if usize::from(*id) >= self.labels.len() => {
                        return Err(TcgIrCacheError::Invalid(
                            "operation references an unknown label",
                        ));
                    }
                    TcgOperand::Helper(name) => {
                        validate_identifier(name, MAX_STRING_BYTES)?;
                    }
                    TcgOperand::Global(index) if usize::from(*index) >= MAX_TEMPS => {
                        return Err(TcgIrCacheError::Invalid(
                            "global temporary index exceeds the TCG limit",
                        ));
                    }
                    _ => {}
                }
            }
            validate_operation_schema(operation, &self.temporaries)?;
        }
        Ok(())
    }

    /// Encode a deterministic, bounded, pointer-free cache record.
    pub fn encode(&self) -> Result<Vec<u8>, TcgIrCacheError> {
        self.validate()?;

        let mut writer = Writer::default();
        writer.bytes.extend_from_slice(MAGIC);
        writer.u16(FORMAT_VERSION);
        writer.u32(0); // payload length is filled after the records are encoded
        write_compatibility(&mut writer, &self.compatibility)?;
        writer.u32(self.guest_size);
        writer.u16(self.instruction_count);

        writer.u16(self.temporaries.len() as u16);
        for temp in &self.temporaries {
            writer.u16(temp.id);
            writer.u16(temp.base_id);
            writer.u8(temp.subindex);
            writer.u8(temp.base_type as u8);
            writer.u8(temp.value_type as u8);
            writer.u8(temp.kind as u8);
        }

        writer.u16(self.labels.len() as u16);
        for label in &self.labels {
            writer.u16(label.id);
            writer.u32(label.operation_index);
        }

        writer.u32(self.operations.len() as u32);
        for operation in &self.operations {
            writer.string(&operation.opcode)?;
            writer.u8(operation.param1);
            writer.u8(operation.param2);
            writer.u8(operation.operands.len() as u8);
            for operand in &operation.operands {
                match operand {
                    TcgOperand::Temp(id) => {
                        writer.u8(0);
                        writer.u16(*id);
                    }
                    TcgOperand::Global(index) => {
                        writer.u8(1);
                        writer.u16(*index);
                    }
                    TcgOperand::Immediate(value) => {
                        writer.u8(2);
                        writer.i64(*value);
                    }
                    TcgOperand::Label(id) => {
                        writer.u8(3);
                        writer.u16(*id);
                    }
                    TcgOperand::Helper(name) => {
                        writer.u8(4);
                        writer.string(name)?;
                    }
                }
            }
        }

        let payload_len = writer
            .bytes
            .len()
            .checked_sub(HEADER_BYTES)
            .ok_or(TcgIrCacheError::Corrupt)?;
        let payload_len = u32::try_from(payload_len).map_err(|_| TcgIrCacheError::TooLarge)?;
        writer.bytes[10..14].copy_from_slice(&payload_len.to_be_bytes());

        let digest = Sha256::digest(&writer.bytes);
        writer.bytes.extend_from_slice(&digest);
        if writer.bytes.len() > MAX_CACHE_BYTES {
            return Err(TcgIrCacheError::TooLarge);
        }
        Ok(writer.bytes)
    }

    /// Persist one encoded block using a same-directory temporary file and atomic rename.
    ///
    /// The file and containing directory are synced before success is reported. The
    /// caller still owns per-VM isolation, quotas, cleanup and process-level locking.
    pub fn write_atomic(&self, path: impl AsRef<Path>) -> Result<(), TcgIrCacheError> {
        let path = path.as_ref();
        let parent = path
            .parent()
            .filter(|parent| !parent.as_os_str().is_empty())
            .unwrap_or_else(|| Path::new("."));
        let mut temporary_name = path.as_os_str().to_os_string();
        temporary_name.push(format!(
            ".tmp-{}-{}",
            std::process::id(),
            TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        let temporary_path = PathBuf::from(temporary_name);

        let result = (|| {
            fs::create_dir_all(parent).map_err(|error| TcgIrCacheError::Io(error.kind()))?;
            let encoded = self.encode()?;
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary_path)
                .map_err(|error| TcgIrCacheError::Io(error.kind()))?;
            file.write_all(&encoded)
                .map_err(|error| TcgIrCacheError::Io(error.kind()))?;
            file.sync_all()
                .map_err(|error| TcgIrCacheError::Io(error.kind()))?;
            drop(file);

            fs::rename(&temporary_path, path).map_err(|error| TcgIrCacheError::Io(error.kind()))?;
            File::open(parent)
                .and_then(|directory| directory.sync_all())
                .map_err(|error| TcgIrCacheError::Io(error.kind()))?;
            Ok(())
        })();

        if result.is_err() {
            let _ = fs::remove_file(&temporary_path);
        }
        result
    }

    /// Read one record without allowing a file to allocate beyond the cache limit.
    pub fn read_file(path: impl AsRef<Path>) -> Result<Self, TcgIrCacheError> {
        let mut file = File::open(path).map_err(|error| TcgIrCacheError::Io(error.kind()))?;
        let mut encoded = Vec::new();
        Read::by_ref(&mut file)
            .take((MAX_CACHE_BYTES + 1) as u64)
            .read_to_end(&mut encoded)
            .map_err(|error| TcgIrCacheError::Io(error.kind()))?;
        if encoded.len() > MAX_CACHE_BYTES {
            return Err(TcgIrCacheError::TooLarge);
        }
        Self::decode(&encoded)
    }

    /// Decode only a complete, authenticated-by-digest record within strict bounds.
    pub fn decode(bytes: &[u8]) -> Result<Self, TcgIrCacheError> {
        if bytes.len() > MAX_CACHE_BYTES {
            return Err(TcgIrCacheError::TooLarge);
        }
        if bytes.len() < HEADER_BYTES + DIGEST_BYTES || &bytes[..8] != MAGIC {
            return Err(TcgIrCacheError::Corrupt);
        }

        let version = u16::from_be_bytes([bytes[8], bytes[9]]);
        if version != FORMAT_VERSION {
            return Err(TcgIrCacheError::UnsupportedVersion);
        }
        let declared_payload =
            u32::from_be_bytes([bytes[10], bytes[11], bytes[12], bytes[13]]) as usize;
        let expected_payload = bytes
            .len()
            .checked_sub(HEADER_BYTES + DIGEST_BYTES)
            .ok_or(TcgIrCacheError::Corrupt)?;
        if declared_payload != expected_payload {
            return Err(TcgIrCacheError::Corrupt);
        }

        let digest_offset = bytes.len() - DIGEST_BYTES;
        let calculated = Sha256::digest(&bytes[..digest_offset]);
        if calculated.as_slice() != &bytes[digest_offset..] {
            return Err(TcgIrCacheError::Corrupt);
        }

        let mut reader = Reader::new(&bytes[HEADER_BYTES..digest_offset]);
        let compatibility = read_compatibility(&mut reader)?;
        let guest_size = reader.u32()?;
        let instruction_count = reader.u16()?;

        let temp_count = usize::from(reader.u16()?);
        if temp_count > MAX_TEMPS {
            return Err(TcgIrCacheError::Corrupt);
        }
        let mut temporaries = Vec::with_capacity(temp_count);
        for _ in 0..temp_count {
            temporaries.push(TcgTempRecord {
                id: reader.u16()?,
                base_id: reader.u16()?,
                subindex: reader.u8()?,
                base_type: TcgValueType::decode(reader.u8()?)?,
                value_type: TcgValueType::decode(reader.u8()?)?,
                kind: TcgTempKind::decode(reader.u8()?)?,
            });
        }

        let label_count = usize::from(reader.u16()?);
        if label_count > MAX_LABELS {
            return Err(TcgIrCacheError::Corrupt);
        }
        let mut labels = Vec::with_capacity(label_count);
        for _ in 0..label_count {
            labels.push(TcgLabelRecord {
                id: reader.u16()?,
                operation_index: reader.u32()?,
            });
        }

        let operation_count = reader.u32()? as usize;
        if operation_count == 0 || operation_count > MAX_OPERATIONS {
            return Err(TcgIrCacheError::Corrupt);
        }
        let mut operations = Vec::with_capacity(operation_count);
        for _ in 0..operation_count {
            let opcode = reader.string(64)?;
            let param1 = reader.u8()?;
            let param2 = reader.u8()?;
            let operand_count = usize::from(reader.u8()?);
            if operand_count > MAX_OPERANDS {
                return Err(TcgIrCacheError::Corrupt);
            }
            let mut operands = Vec::with_capacity(operand_count);
            for _ in 0..operand_count {
                let operand = match reader.u8()? {
                    0 => TcgOperand::Temp(reader.u16()?),
                    1 => TcgOperand::Global(reader.u16()?),
                    2 => TcgOperand::Immediate(reader.i64()?),
                    3 => TcgOperand::Label(reader.u16()?),
                    4 => TcgOperand::Helper(reader.string(MAX_STRING_BYTES)?),
                    _ => return Err(TcgIrCacheError::Corrupt),
                };
                operands.push(operand);
            }
            operations.push(TcgIrOperation {
                opcode,
                param1,
                param2,
                operands,
            });
        }

        if !reader.is_empty() {
            return Err(TcgIrCacheError::Corrupt);
        }
        let block = Self {
            compatibility,
            guest_size,
            instruction_count,
            temporaries,
            labels,
            operations,
        };
        block.validate()?;
        Ok(block)
    }

    pub fn is_compatible_with(&self, candidate: &TcgIrCompatibility) -> bool {
        self.compatibility.exactly_matches(candidate)
    }
}

fn validate_identifier(value: &str, max_bytes: usize) -> Result<(), TcgIrCacheError> {
    if value.is_empty()
        || value.len() > max_bytes
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'.' | b':'))
    {
        return Err(TcgIrCacheError::Invalid("invalid stable identifier"));
    }
    Ok(())
}

fn write_compatibility(
    writer: &mut Writer,
    value: &TcgIrCompatibility,
) -> Result<(), TcgIrCacheError> {
    value.validate()?;
    for field in [
        &value.qemu_build_id,
        &value.guest_arch,
        &value.guest_cpu_model,
        &value.guest_build_id,
        &value.host_arch,
        &value.host_abi,
    ] {
        writer.string(field)?;
    }
    writer.u8(value.host_endian.encode());
    writer.u32(value.host_page_size);
    writer.u16(value.host_cpu_features.len() as u16);
    for feature in &value.host_cpu_features {
        writer.string(feature)?;
    }
    writer.u64(value.guest_pc);
    writer.u64(value.guest_cs_base);
    writer.u64(value.tb_flags);
    writer.u64(value.tb_cflags);
    writer.u32(value.mmu_index);
    writer.u8(value.physical_pages.len() as u8);
    for page in &value.physical_pages {
        writer.u64(*page);
    }
    writer.bytes.extend_from_slice(&value.guest_code_sha256);
    writer.bytes.extend_from_slice(&value.tb_context_sha256);
    Ok(())
}

fn read_compatibility(reader: &mut Reader<'_>) -> Result<TcgIrCompatibility, TcgIrCacheError> {
    let qemu_build_id = reader.string(MAX_STRING_BYTES)?;
    let guest_arch = reader.string(MAX_STRING_BYTES)?;
    let guest_cpu_model = reader.string(MAX_STRING_BYTES)?;
    let guest_build_id = reader.string(MAX_STRING_BYTES)?;
    let host_arch = reader.string(MAX_STRING_BYTES)?;
    let host_abi = reader.string(MAX_STRING_BYTES)?;
    let host_endian = TcgEndian::decode(reader.u8()?)?;
    let host_page_size = reader.u32()?;

    let feature_count = usize::from(reader.u16()?);
    if feature_count > MAX_CPU_FEATURES {
        return Err(TcgIrCacheError::Corrupt);
    }
    let mut host_cpu_features = Vec::with_capacity(feature_count);
    for _ in 0..feature_count {
        host_cpu_features.push(reader.string(MAX_STRING_BYTES)?);
    }

    let guest_pc = reader.u64()?;
    let guest_cs_base = reader.u64()?;
    let tb_flags = reader.u64()?;
    let tb_cflags = reader.u64()?;
    let mmu_index = reader.u32()?;
    let page_count = usize::from(reader.u8()?);
    if page_count == 0 || page_count > 2 {
        return Err(TcgIrCacheError::Corrupt);
    }
    let mut physical_pages = Vec::with_capacity(page_count);
    for _ in 0..page_count {
        physical_pages.push(reader.u64()?);
    }
    let mut guest_code_sha256 = [0; 32];
    guest_code_sha256.copy_from_slice(reader.take(32)?);
    let mut tb_context_sha256 = [0; 32];
    tb_context_sha256.copy_from_slice(reader.take(32)?);

    let value = TcgIrCompatibility {
        qemu_build_id,
        guest_arch,
        guest_cpu_model,
        guest_build_id,
        host_arch,
        host_abi,
        host_endian,
        host_page_size,
        host_cpu_features,
        guest_pc,
        guest_cs_base,
        tb_flags,
        tb_cflags,
        mmu_index,
        physical_pages,
        guest_code_sha256,
        tb_context_sha256,
    };
    value.validate().map_err(|_| TcgIrCacheError::Corrupt)?;
    Ok(value)
}

#[derive(Default)]
struct Writer {
    bytes: Vec<u8>,
}

impl Writer {
    fn u8(&mut self, value: u8) {
        self.bytes.push(value);
    }
    fn u16(&mut self, value: u16) {
        self.bytes.extend_from_slice(&value.to_be_bytes());
    }
    fn u32(&mut self, value: u32) {
        self.bytes.extend_from_slice(&value.to_be_bytes());
    }
    fn u64(&mut self, value: u64) {
        self.bytes.extend_from_slice(&value.to_be_bytes());
    }
    fn i64(&mut self, value: i64) {
        self.bytes.extend_from_slice(&value.to_be_bytes());
    }
    fn string(&mut self, value: &str) -> Result<(), TcgIrCacheError> {
        validate_identifier(value, MAX_STRING_BYTES)?;
        let length = u16::try_from(value.len()).map_err(|_| TcgIrCacheError::TooLarge)?;
        self.u16(length);
        self.bytes.extend_from_slice(value.as_bytes());
        Ok(())
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }
    fn take(&mut self, length: usize) -> Result<&'a [u8], TcgIrCacheError> {
        let end = self
            .offset
            .checked_add(length)
            .ok_or(TcgIrCacheError::Corrupt)?;
        let value = self
            .bytes
            .get(self.offset..end)
            .ok_or(TcgIrCacheError::Corrupt)?;
        self.offset = end;
        Ok(value)
    }
    fn u8(&mut self) -> Result<u8, TcgIrCacheError> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, TcgIrCacheError> {
        Ok(u16::from_be_bytes(
            self.take(2)?
                .try_into()
                .map_err(|_| TcgIrCacheError::Corrupt)?,
        ))
    }
    fn u32(&mut self) -> Result<u32, TcgIrCacheError> {
        Ok(u32::from_be_bytes(
            self.take(4)?
                .try_into()
                .map_err(|_| TcgIrCacheError::Corrupt)?,
        ))
    }
    fn u64(&mut self) -> Result<u64, TcgIrCacheError> {
        Ok(u64::from_be_bytes(
            self.take(8)?
                .try_into()
                .map_err(|_| TcgIrCacheError::Corrupt)?,
        ))
    }
    fn i64(&mut self) -> Result<i64, TcgIrCacheError> {
        Ok(i64::from_be_bytes(
            self.take(8)?
                .try_into()
                .map_err(|_| TcgIrCacheError::Corrupt)?,
        ))
    }
    fn string(&mut self, maximum: usize) -> Result<String, TcgIrCacheError> {
        let length = usize::from(self.u16()?);
        if length == 0 || length > maximum {
            return Err(TcgIrCacheError::Corrupt);
        }
        let bytes = self.take(length)?;
        let value = std::str::from_utf8(bytes).map_err(|_| TcgIrCacheError::Corrupt)?;
        validate_identifier(value, maximum).map_err(|_| TcgIrCacheError::Corrupt)?;
        Ok(value.to_owned())
    }
    fn is_empty(&self) -> bool {
        self.offset == self.bytes.len()
    }
}

const MAX_L2_ENTRIES: usize = 65_536;
const MAX_L2_BYTES: u64 = 4 * 1024 * 1024 * 1024;
const STALE_TEMP_FILE_AGE: Duration = Duration::from_secs(5 * 60);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcgIrL2Stats {
    pub entries: usize,
    pub bytes: u64,
}

/// VM-isolated native L2 store for canonical TCG IR records.
///
/// All filesystem operations are intended for a cache worker, not a vCPU thread:
/// loading can block on disk. The QEMU adapter must prefetch on a worker and pass
/// validated records into the translation path. This store does not itself attach
/// to QEMU's TCG translator.
pub struct TcgIrL2Store {
    root: PathBuf,
    max_entries: usize,
    max_bytes: u64,
    lock: Mutex<()>,
}

impl TcgIrL2Store {
    pub fn new(
        root: impl AsRef<Path>,
        max_entries: usize,
        max_bytes: u64,
    ) -> Result<Self, TcgIrCacheError> {
        if !(1..=MAX_L2_ENTRIES).contains(&max_entries) {
            return Err(TcgIrCacheError::Invalid("invalid L2 entry quota"));
        }
        if !(1..=MAX_L2_BYTES).contains(&max_bytes) {
            return Err(TcgIrCacheError::Invalid("invalid L2 byte quota"));
        }

        let root = root.as_ref().to_path_buf();
        fs::create_dir_all(&root).map_err(|error| TcgIrCacheError::Io(error.kind()))?;
        let metadata =
            fs::symlink_metadata(&root).map_err(|error| TcgIrCacheError::Io(error.kind()))?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(TcgIrCacheError::Invalid("L2 root must be a real directory"));
        }

        Ok(Self {
            root,
            max_entries,
            max_bytes,
            lock: Mutex::new(()),
        })
    }

    /// Persist one VM-owned entry; the guest-code fingerprint must already reflect
    /// the current translated bytes. The caller supplies the fresh context.
    pub fn store(&self, vm_id: &str, block: &TcgIrBlock) -> Result<(), TcgIrCacheError> {
        validate_vm_id(vm_id)?;
        let encoded = block.encode()?;
        if encoded.len() as u64 > self.max_bytes {
            return Err(TcgIrCacheError::TooLarge);
        }

        let _guard = self
            .lock
            .lock()
            .map_err(|_| TcgIrCacheError::Invalid("L2 cache lock poisoned"))?;
        let _process_guard = ProcessFileLock::acquire(&self.root)?;
        let directory = self.vm_directory(vm_id);
        self.ensure_real_directory(&directory)?;
        block.write_atomic(self.entry_path(vm_id, &block.compatibility)?)?;
        self.cleanup_locked()?;
        Ok(())
    }

    /// Return a record only if every compatibility component exactly matches the
    /// caller's freshly constructed identity. Corrupt or unsupported records become
    /// safe cache misses and are removed.
    pub fn load(
        &self,
        vm_id: &str,
        expected: &TcgIrCompatibility,
    ) -> Result<Option<TcgIrBlock>, TcgIrCacheError> {
        validate_vm_id(vm_id)?;
        expected.validate()?;
        let _guard = self
            .lock
            .lock()
            .map_err(|_| TcgIrCacheError::Invalid("L2 cache lock poisoned"))?;
        let _process_guard = ProcessFileLock::acquire(&self.root)?;
        let directory = self.vm_directory(vm_id);
        if !directory.exists() {
            return Ok(None);
        }
        self.ensure_real_directory(&directory)?;
        let path = self.entry_path(vm_id, expected)?;
        let metadata = match fs::symlink_metadata(&path) {
            Ok(value) => value,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(TcgIrCacheError::Io(error.kind())),
        };
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            let _ = fs::remove_file(&path);
            return Ok(None);
        }

        match TcgIrBlock::read_file(&path) {
            Ok(block) if block.is_compatible_with(expected) => {
                let _ = fs::File::open(&path).and_then(|file| file.set_modified(SystemTime::now()));
                Ok(Some(block))
            }
            Ok(_)
            | Err(TcgIrCacheError::Corrupt)
            | Err(TcgIrCacheError::UnsupportedVersion)
            | Err(TcgIrCacheError::TooLarge) => {
                let _ = fs::remove_file(&path);
                Ok(None)
            }
            Err(TcgIrCacheError::Io(kind)) if kind == std::io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(error),
        }
    }

    pub fn invalidate(
        &self,
        vm_id: &str,
        compatibility: &TcgIrCompatibility,
    ) -> Result<(), TcgIrCacheError> {
        validate_vm_id(vm_id)?;
        compatibility.validate()?;
        let _guard = self
            .lock
            .lock()
            .map_err(|_| TcgIrCacheError::Invalid("L2 cache lock poisoned"))?;
        let _process_guard = ProcessFileLock::acquire(&self.root)?;
        let directory = self.vm_directory(vm_id);
        let metadata = match fs::symlink_metadata(&directory) {
            Ok(value) => value,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(TcgIrCacheError::Io(error.kind())),
        };
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(TcgIrCacheError::Invalid(
                "VM cache path must be a real directory",
            ));
        }
        let path = self.entry_path(vm_id, compatibility)?;
        match fs::remove_file(path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(TcgIrCacheError::Io(error.kind())),
        }
    }

    pub fn invalidate_vm(&self, vm_id: &str) -> Result<(), TcgIrCacheError> {
        validate_vm_id(vm_id)?;
        let _guard = self
            .lock
            .lock()
            .map_err(|_| TcgIrCacheError::Invalid("L2 cache lock poisoned"))?;
        let _process_guard = ProcessFileLock::acquire(&self.root)?;
        let directory = self.vm_directory(vm_id);
        match fs::symlink_metadata(&directory) {
            Ok(metadata) if metadata.file_type().is_symlink() => {
                fs::remove_file(directory).map_err(|error| TcgIrCacheError::Io(error.kind()))
            }
            Ok(metadata) if metadata.is_dir() => {
                fs::remove_dir_all(directory).map_err(|error| TcgIrCacheError::Io(error.kind()))
            }
            Ok(_) => {
                let _ = fs::remove_file(directory);
                Ok(())
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(TcgIrCacheError::Io(error.kind())),
        }
    }

    pub fn cleanup(&self) -> Result<TcgIrL2Stats, TcgIrCacheError> {
        let _guard = self
            .lock
            .lock()
            .map_err(|_| TcgIrCacheError::Invalid("L2 cache lock poisoned"))?;
        let _process_guard = ProcessFileLock::acquire(&self.root)?;
        self.cleanup_locked()
    }

    pub fn stats(&self) -> Result<TcgIrL2Stats, TcgIrCacheError> {
        let _guard = self
            .lock
            .lock()
            .map_err(|_| TcgIrCacheError::Invalid("L2 cache lock poisoned"))?;
        let _process_guard = ProcessFileLock::acquire(&self.root)?;
        self.scan_entries().map(|entries| TcgIrL2Stats {
            entries: entries.len(),
            bytes: entries
                .iter()
                .fold(0_u64, |total, entry| total.saturating_add(entry.bytes)),
        })
    }

    /// Load the most recently used entries for one VM for a memory-resident L1.
    ///
    /// This method performs filesystem I/O and MUST run on a cache worker, never
    /// from a vCPU thread. Corrupt records are deleted as safe misses.
    pub fn preload_vm(
        &self,
        vm_id: &str,
        max_entries: usize,
        max_bytes: u64,
    ) -> Result<Vec<TcgIrBlock>, TcgIrCacheError> {
        validate_vm_id(vm_id)?;
        if max_entries == 0 || max_bytes == 0 {
            return Err(TcgIrCacheError::Invalid("invalid L1 preload quota"));
        }
        let _guard = self
            .lock
            .lock()
            .map_err(|_| TcgIrCacheError::Invalid("L2 cache lock poisoned"))?;
        let _process_guard = ProcessFileLock::acquire(&self.root)?;
        let directory = self.vm_directory(vm_id);
        match fs::symlink_metadata(&directory) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => {
                return Err(TcgIrCacheError::Invalid(
                    "VM cache path must be a real directory",
                ));
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(error) => return Err(TcgIrCacheError::Io(error.kind())),
        }

        let mut candidates = Vec::new();
        for entry in fs::read_dir(&directory).map_err(|error| TcgIrCacheError::Io(error.kind()))? {
            let entry = entry.map_err(|error| TcgIrCacheError::Io(error.kind()))?;
            let path = entry.path();
            let metadata = match fs::symlink_metadata(&path) {
                Ok(metadata) => metadata,
                Err(_) => continue,
            };
            if metadata.file_type().is_symlink()
                || !metadata.is_file()
                || path.extension().and_then(|value| value.to_str()) != Some("pvir")
            {
                continue;
            }
            candidates.push(L2Entry {
                path,
                bytes: metadata.len(),
                modified: metadata.modified().unwrap_or(SystemTime::UNIX_EPOCH),
            });
        }
        candidates.sort_by_key(|entry| std::cmp::Reverse(entry.modified));

        let mut loaded = Vec::new();
        let mut total_bytes = 0_u64;
        for entry in candidates {
            if loaded.len() >= max_entries || total_bytes.saturating_add(entry.bytes) > max_bytes {
                continue;
            }
            match TcgIrBlock::read_file(&entry.path) {
                Ok(block) => {
                    total_bytes = total_bytes.saturating_add(entry.bytes);
                    loaded.push(block);
                }
                Err(TcgIrCacheError::Corrupt)
                | Err(TcgIrCacheError::UnsupportedVersion)
                | Err(TcgIrCacheError::TooLarge)
                | Err(TcgIrCacheError::Invalid(_)) => {
                    let _ = fs::remove_file(&entry.path);
                }
                Err(TcgIrCacheError::Io(std::io::ErrorKind::NotFound)) => {}
                Err(error) => return Err(error),
            }
        }
        Ok(loaded)
    }

    fn vm_directory(&self, vm_id: &str) -> PathBuf {
        let digest = Sha256::digest(vm_id.as_bytes());
        self.root.join(hex_digest(&digest))
    }

    fn entry_path(
        &self,
        vm_id: &str,
        compatibility: &TcgIrCompatibility,
    ) -> Result<PathBuf, TcgIrCacheError> {
        let fingerprint = compatibility.fingerprint()?;
        Ok(self
            .vm_directory(vm_id)
            .join(format!("{}.pvir", hex_digest(&fingerprint))))
    }

    fn ensure_real_directory(&self, path: &Path) -> Result<(), TcgIrCacheError> {
        fs::create_dir_all(path).map_err(|error| TcgIrCacheError::Io(error.kind()))?;
        let metadata =
            fs::symlink_metadata(path).map_err(|error| TcgIrCacheError::Io(error.kind()))?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err(TcgIrCacheError::Invalid(
                "VM cache path must be a real directory",
            ));
        }
        Ok(())
    }

    fn scan_entries(&self) -> Result<Vec<L2Entry>, TcgIrCacheError> {
        let mut entries = Vec::new();
        let now = SystemTime::now();
        for vm_entry in
            fs::read_dir(&self.root).map_err(|error| TcgIrCacheError::Io(error.kind()))?
        {
            let vm_entry = vm_entry.map_err(|error| TcgIrCacheError::Io(error.kind()))?;
            let vm_path = vm_entry.path();
            let vm_metadata = match fs::symlink_metadata(&vm_path) {
                Ok(value) => value,
                Err(_) => continue,
            };
            if vm_metadata.file_type().is_symlink() || !vm_metadata.is_dir() {
                continue;
            }

            for file_entry in
                fs::read_dir(&vm_path).map_err(|error| TcgIrCacheError::Io(error.kind()))?
            {
                let file_entry = file_entry.map_err(|error| TcgIrCacheError::Io(error.kind()))?;
                let path = file_entry.path();
                let metadata = match fs::symlink_metadata(&path) {
                    Ok(value) => value,
                    Err(_) => continue,
                };
                if metadata.file_type().is_symlink() || !metadata.is_file() {
                    continue;
                }

                let name = file_entry.file_name();
                let name = name.to_string_lossy();
                if name.contains(".tmp-") {
                    let age = now
                        .duration_since(metadata.modified().unwrap_or(SystemTime::UNIX_EPOCH))
                        .unwrap_or_default();
                    if age >= STALE_TEMP_FILE_AGE {
                        let _ = fs::remove_file(path);
                    }
                    continue;
                }
                if path.extension().and_then(|value| value.to_str()) != Some("pvir") {
                    continue;
                }

                entries.push(L2Entry {
                    path,
                    bytes: metadata.len(),
                    modified: metadata.modified().unwrap_or(SystemTime::UNIX_EPOCH),
                });
            }
        }
        Ok(entries)
    }

    fn cleanup_locked(&self) -> Result<TcgIrL2Stats, TcgIrCacheError> {
        let mut entries = self.scan_entries()?;
        let mut bytes = entries
            .iter()
            .fold(0_u64, |total, entry| total.saturating_add(entry.bytes));
        entries.sort_by_key(|entry| entry.modified);

        while (entries.len() > self.max_entries || bytes > self.max_bytes) && !entries.is_empty() {
            let oldest = entries.remove(0);
            match fs::remove_file(&oldest.path) {
                Ok(()) => bytes = bytes.saturating_sub(oldest.bytes),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                    bytes = bytes.saturating_sub(oldest.bytes);
                }
                Err(error) => return Err(TcgIrCacheError::Io(error.kind())),
            }
        }

        Ok(TcgIrL2Stats {
            entries: entries.len(),
            bytes,
        })
    }
}

#[derive(Debug)]
struct L2Entry {
    path: PathBuf,
    bytes: u64,
    modified: SystemTime,
}

fn validate_vm_id(vm_id: &str) -> Result<(), TcgIrCacheError> {
    if vm_id.is_empty() || vm_id.len() > 1024 {
        return Err(TcgIrCacheError::Invalid("invalid VM identifier"));
    }
    Ok(())
}

fn hex_digest(digest: &[u8]) -> String {
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn process_lock_serializes_independent_open_file_descriptions() {
        let root = std::env::temp_dir().join(format!(
            "phoenixvm-tcg-process-lock-{}-{}",
            std::process::id(),
            TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&root).unwrap();

        let first = ProcessFileLock::acquire(&root).unwrap();
        let worker_root = root.clone();
        let (sender, receiver) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            let _second = ProcessFileLock::acquire(&worker_root).unwrap();
            sender.send(()).unwrap();
        });

        // The second independent open must block until the first guard releases LOCK_EX.
        assert!(matches!(
            receiver.recv_timeout(Duration::from_millis(100)),
            Err(std::sync::mpsc::RecvTimeoutError::Timeout)
        ));
        drop(first);
        receiver.recv_timeout(Duration::from_secs(2)).unwrap();
        worker.join().unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn process_lock_rejects_symlink_redirection() {
        use std::os::unix::fs::symlink;

        let root = std::env::temp_dir().join(format!(
            "phoenixvm-tcg-process-lock-symlink-{}-{}",
            std::process::id(),
            TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&root).unwrap();
        let target = root.join("untrusted-target");
        File::create(&target).unwrap();
        symlink(&target, root.join(".tcg-ir-l2.lock")).unwrap();

        assert!(ProcessFileLock::acquire(&root).is_err());
        fs::remove_dir_all(root).unwrap();
    }

    fn compatibility() -> TcgIrCompatibility {
        TcgIrCompatibility {
            qemu_build_id: "qemu-11.1.3-phoenixvm-pcache-abi1".into(),
            guest_arch: "x86_64".into(),
            guest_cpu_model: "qemu64".into(),
            guest_build_id: "android-x86_64-test-build".into(),
            host_arch: "aarch64".into(),
            host_abi: "android-bionic-api35".into(),
            host_endian: TcgEndian::Little,
            host_page_size: 4096,
            host_cpu_features: vec!["aes".into(), "asimd".into(), "crc32".into()],
            guest_pc: 0x1000,
            guest_cs_base: 0,
            tb_flags: 0x12,
            tb_cflags: 0x20,
            mmu_index: 0,
            physical_pages: vec![0x8000],
            guest_code_sha256: [0x11; 32],
            tb_context_sha256: [0x22; 32],
        }
    }

    fn sample_block() -> TcgIrBlock {
        TcgIrBlock {
            compatibility: compatibility(),
            guest_size: 12,
            instruction_count: 3,
            temporaries: vec![
                TcgTempRecord {
                    id: 0,
                    base_id: 0,
                    subindex: 0,
                    base_type: TcgValueType::I64,
                    value_type: TcgValueType::I64,
                    kind: TcgTempKind::Tb,
                },
                TcgTempRecord {
                    id: 1,
                    base_id: 1,
                    subindex: 0,
                    base_type: TcgValueType::I64,
                    value_type: TcgValueType::I64,
                    kind: TcgTempKind::Ebb,
                },
            ],
            labels: vec![TcgLabelRecord {
                id: 0,
                operation_index: 6,
            }],
            operations: vec![
                TcgIrOperation {
                    opcode: "insn_start".into(),
                    param1: 0,
                    param2: 0,
                    operands: vec![
                        TcgOperand::Immediate(0x1000),
                        TcgOperand::Immediate(0),
                        TcgOperand::Immediate(0),
                    ],
                },
                TcgIrOperation {
                    opcode: "mov".into(),
                    param1: TcgValueType::I64 as u8,
                    param2: 0,
                    operands: vec![TcgOperand::Temp(0), TcgOperand::Global(0)],
                },
                TcgIrOperation {
                    opcode: "insn_start".into(),
                    param1: 0,
                    param2: 0,
                    operands: vec![
                        TcgOperand::Immediate(0x1004),
                        TcgOperand::Immediate(0),
                        TcgOperand::Immediate(0),
                    ],
                },
                TcgIrOperation {
                    opcode: "add".into(),
                    param1: TcgValueType::I64 as u8,
                    param2: 0,
                    operands: vec![
                        TcgOperand::Temp(1),
                        TcgOperand::Temp(0),
                        TcgOperand::Global(0),
                    ],
                },
                TcgIrOperation {
                    opcode: "insn_start".into(),
                    param1: 0,
                    param2: 0,
                    operands: vec![
                        TcgOperand::Immediate(0x1008),
                        TcgOperand::Immediate(0),
                        TcgOperand::Immediate(0),
                    ],
                },
                TcgIrOperation {
                    opcode: "br".into(),
                    param1: 0,
                    param2: 0,
                    operands: vec![TcgOperand::Label(0)],
                },
                TcgIrOperation {
                    opcode: "set_label".into(),
                    param1: 0,
                    param2: 0,
                    operands: vec![TcgOperand::Label(0)],
                },
            ],
        }
    }

    #[test]
    fn codec_rejects_unknown_opcodes_and_untyped_operands() {
        let mut block = sample_block();
        block.operations[1].opcode = "unknown_process_pointer_op".into();
        assert!(matches!(block.validate(), Err(TcgIrCacheError::Invalid(_))));

        let mut block = sample_block();
        block.operations[1].operands[1] = TcgOperand::Helper("unregistered_helper".into());
        assert!(matches!(block.validate(), Err(TcgIrCacheError::Invalid(_))));
    }

    #[test]
    fn codec_requires_complete_insn_start_and_relocation_safe_tb_exit() {
        let mut block = sample_block();
        block.operations[0].operands.pop();
        assert!(matches!(block.validate(), Err(TcgIrCacheError::Invalid(_))));

        let mut block = sample_block();
        block.operations.push(TcgIrOperation {
            opcode: "exit_tb".into(),
            param1: 0,
            param2: 0,
            operands: vec![TcgOperand::Immediate(0x1234_5678)],
        });
        assert!(matches!(block.validate(), Err(TcgIrCacheError::Invalid(_))));

        let mut block = sample_block();
        block.operations.push(TcgIrOperation {
            opcode: "exit_tb".into(),
            param1: 0,
            param2: 0,
            operands: vec![TcgOperand::Immediate(3)],
        });
        assert!(block.validate().is_ok());

        let mut block = sample_block();
        block.operations.push(TcgIrOperation {
            opcode: "exit_tb".into(),
            param1: 0,
            param2: 0,
            operands: vec![TcgOperand::Immediate(2)],
        });
        assert!(block.validate().is_err());
    }

    #[test]
    fn codec_rejects_global_index_outside_tcg_bounds() {
        let mut block = sample_block();
        block.operations[1].operands[1] = TcgOperand::Global(MAX_TEMPS as u16);
        assert!(matches!(block.validate(), Err(TcgIrCacheError::Invalid(_))));
    }

    #[test]
    fn vm_l2_store_isolates_owners_and_invalidates_selectively() {
        let root = std::env::temp_dir().join(format!(
            "phoenixvm-tcg-l2-{}-{}",
            std::process::id(),
            TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        let store = TcgIrL2Store::new(&root, 8, 8 * 1024 * 1024).unwrap();
        let block = sample_block();
        let key = block.compatibility.clone();

        store.store("vm-one", &block).unwrap();
        assert_eq!(store.load("vm-one", &key).unwrap(), Some(block.clone()));
        assert_eq!(store.load("vm-two", &key).unwrap(), None);

        let mut changed = key.clone();
        changed.guest_code_sha256[0] ^= 0x80;
        assert_eq!(store.load("vm-one", &changed).unwrap(), None);

        store.invalidate("vm-one", &key).unwrap();
        assert_eq!(store.load("vm-one", &key).unwrap(), None);
        assert_eq!(store.stats().unwrap().entries, 0);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn vm_l2_store_enforces_lru_entry_quota() {
        let root = std::env::temp_dir().join(format!(
            "phoenixvm-tcg-l2-quota-{}-{}",
            std::process::id(),
            TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        let store = TcgIrL2Store::new(&root, 1, 8 * 1024 * 1024).unwrap();
        let first = sample_block();
        let mut second = sample_block();
        second.compatibility.guest_pc += 4;

        store.store("vm-one", &first).unwrap();
        let first_path = store.entry_path("vm-one", &first.compatibility).unwrap();
        fs::File::open(first_path)
            .unwrap()
            .set_modified(SystemTime::UNIX_EPOCH)
            .unwrap();

        store.store("vm-one", &second).unwrap();
        assert_eq!(store.stats().unwrap().entries, 1);
        assert_eq!(store.load("vm-one", &first.compatibility).unwrap(), None);
        assert_eq!(
            store.load("vm-one", &second.compatibility).unwrap(),
            Some(second)
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn vm_l2_store_deletes_corrupt_records_as_safe_misses() {
        let root = std::env::temp_dir().join(format!(
            "phoenixvm-tcg-l2-corrupt-{}-{}",
            std::process::id(),
            TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        let store = TcgIrL2Store::new(&root, 8, 8 * 1024 * 1024).unwrap();
        let block = sample_block();
        store.store("vm-one", &block).unwrap();

        let path = store.entry_path("vm-one", &block.compatibility).unwrap();
        let mut encoded = fs::read(&path).unwrap();
        let last = encoded.len() - 1;
        encoded[last] ^= 0x20;
        fs::write(&path, encoded).unwrap();

        assert_eq!(store.load("vm-one", &block.compatibility).unwrap(), None);
        assert!(!path.exists());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn round_trip_preserves_ir_and_compatibility() {
        let block = sample_block();
        let encoded = block.encode().unwrap();
        let decoded = TcgIrBlock::decode(&encoded).unwrap();
        assert_eq!(decoded, block);
        assert!(decoded.is_compatible_with(&compatibility()));
        assert_eq!(
            block.compatibility.fingerprint().unwrap(),
            compatibility().fingerprint().unwrap()
        );
    }

    #[test]
    fn strict_schema_accepts_only_defined_operation_parameters() {
        let block = sample_block();
        let decoded = TcgIrBlock::decode(&block.encode().unwrap()).unwrap();
        assert_eq!(decoded.operations[1].param1, TcgValueType::I64 as u8);
        assert_eq!(decoded.operations[1].param2, 0);
        assert_eq!(decoded, block);

        let mut invalid = sample_block();
        invalid.operations[1].param2 = 0xff;
        assert!(matches!(invalid.encode(), Err(TcgIrCacheError::Invalid(_))));

        invalid = sample_block();
        invalid.operations[1].param1 = 0xa5;
        assert!(matches!(invalid.encode(), Err(TcgIrCacheError::Invalid(_))));
    }

    #[test]
    fn encoding_is_deterministic() {
        let block = sample_block();
        assert_eq!(block.encode().unwrap(), block.encode().unwrap());
    }

    #[test]
    fn any_compatibility_difference_rejects_reuse() {
        let block = sample_block();
        let mut candidate = compatibility();
        candidate.guest_code_sha256[0] ^= 1;
        assert!(!block.is_compatible_with(&candidate));

        candidate = compatibility();
        candidate.tb_flags ^= 1;
        assert!(!block.is_compatible_with(&candidate));

        candidate = compatibility();
        candidate.qemu_build_id = "qemu-11.1.3-different-patch".into();
        assert!(!block.is_compatible_with(&candidate));

        candidate = compatibility();
        candidate.host_cpu_features.pop();
        assert!(!block.is_compatible_with(&candidate));
    }

    #[test]
    fn payload_digest_detects_mutation() {
        let mut encoded = sample_block().encode().unwrap();
        let middle = encoded.len() / 2;
        encoded[middle] ^= 0x40;
        assert_eq!(TcgIrBlock::decode(&encoded), Err(TcgIrCacheError::Corrupt));
    }

    #[test]
    fn truncated_and_trailing_data_are_rejected() {
        let encoded = sample_block().encode().unwrap();
        assert_eq!(
            TcgIrBlock::decode(&encoded[..encoded.len() - 1]),
            Err(TcgIrCacheError::Corrupt)
        );

        let mut with_trailing = encoded;
        with_trailing.push(0);
        assert_eq!(
            TcgIrBlock::decode(&with_trailing),
            Err(TcgIrCacheError::Corrupt)
        );
    }

    #[test]
    fn rejects_unknown_temporary_and_label_references() {
        let mut block = sample_block();
        block.operations[0].operands[0] = TcgOperand::Temp(99);
        assert!(matches!(block.encode(), Err(TcgIrCacheError::Invalid(_))));

        let mut block = sample_block();
        block.operations[5].operands[0] = TcgOperand::Label(99);
        assert!(matches!(block.encode(), Err(TcgIrCacheError::Invalid(_))));
    }

    #[test]
    fn rejects_missing_insn_start_markers_and_mismatched_label_definitions() {
        let mut block = sample_block();
        block.instruction_count = 2;
        assert!(matches!(block.encode(), Err(TcgIrCacheError::Invalid(_))));

        block = sample_block();
        block.labels[0].operation_index = 5;
        assert!(matches!(block.encode(), Err(TcgIrCacheError::Invalid(_))));

        block = sample_block();
        block.operations[6].opcode = "nop".into();
        assert!(matches!(block.encode(), Err(TcgIrCacheError::Invalid(_))));
    }

    #[test]
    fn insn_start_markers_and_label_definition_survive_round_trip() {
        let block = sample_block();
        let decoded = TcgIrBlock::decode(&block.encode().unwrap()).unwrap();
        let addresses: Vec<i64> = decoded
            .operations
            .iter()
            .filter(|operation| operation.opcode == "insn_start")
            .filter_map(|operation| match operation.operands.as_slice() {
                [TcgOperand::Immediate(value), _, _] => Some(*value),
                _ => None,
            })
            .collect();
        assert_eq!(addresses, vec![0x1000, 0x1004, 0x1008]);
        assert_eq!(decoded.labels[0].operation_index, 6);
    }

    #[test]
    fn rejects_unsorted_features_and_invalid_page_size() {
        let mut value = compatibility();
        value.host_cpu_features = vec!["asimd".into(), "aes".into()];
        assert!(matches!(value.validate(), Err(TcgIrCacheError::Invalid(_))));

        value = compatibility();
        value.host_page_size = 3000;
        assert!(matches!(value.validate(), Err(TcgIrCacheError::Invalid(_))));
    }

    #[test]
    fn rejects_non_symbolic_names_and_oversized_input() {
        let mut block = sample_block();
        block.operations[0].opcode = "bad opcode!".into();
        assert!(matches!(block.encode(), Err(TcgIrCacheError::Invalid(_))));

        assert_eq!(
            TcgIrBlock::decode(&vec![0; MAX_CACHE_BYTES + 1]),
            Err(TcgIrCacheError::TooLarge)
        );
    }

    #[test]
    fn helper_calls_are_rejected_without_target_specific_registry() {
        let mut block = sample_block();
        block.operations.push(TcgIrOperation {
            opcode: "call".into(),
            param1: TcgValueType::I64 as u8,
            param2: 0,
            operands: vec![
                TcgOperand::Temp(1),
                TcgOperand::Helper("helper_cpuid".into()),
            ],
        });

        assert!(matches!(block.encode(), Err(TcgIrCacheError::Invalid(_))));
    }
    #[test]
    fn atomic_file_round_trip_rejects_corruption() {
        let block = sample_block();
        let unique = TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let directory =
            std::env::temp_dir().join(format!("phoenixvm-tcg-ir-{}-{unique}", std::process::id()));
        let path = directory.join("block.pvir");
        block.write_atomic(&path).unwrap();
        assert_eq!(TcgIrBlock::read_file(&path).unwrap(), block);

        let mut bytes = fs::read(&path).unwrap();
        let last = bytes.len() - 1;
        bytes[last] ^= 1;
        fs::write(&path, bytes).unwrap();
        assert_eq!(TcgIrBlock::read_file(&path), Err(TcgIrCacheError::Corrupt));
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_inconsistent_multislot_temp_groups() {
        let mut block = sample_block();
        block.temporaries.push(TcgTempRecord {
            id: 2,
            base_id: 0,
            subindex: 2,
            base_type: TcgValueType::I128,
            value_type: TcgValueType::I64,
            kind: TcgTempKind::Tb,
        });
        assert!(matches!(block.encode(), Err(TcgIrCacheError::Invalid(_))));
    }
}
