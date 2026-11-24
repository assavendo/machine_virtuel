package com.assavendo.machinevirtuel.runtime
import android.content.Context
import com.assavendo.machinevirtuel.vm.BackendKind
import java.io.File
class RuntimeRegistry(context:Context){
 private val root=File(context.filesDir,"runtimes").apply{mkdirs()}
 fun binaryFor(backend:BackendKind):File=File(root,backend.binaryName)
 fun isInstalled(backend:BackendKind):Boolean=binaryFor(backend).canExecute()
 fun rootPath():String=root.absolutePath
}
