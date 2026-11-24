package com.assavendo.machinevirtuel.cache
import android.content.Context
import java.io.File
import java.security.MessageDigest
data class TranslationCacheStats(val files:Int,val bytes:Long)
class TranslationCacheManager(context:Context){
 private val root=File(context.filesDir,"translation-cache").apply{mkdirs()}
 fun fingerprint(vararg components:String):String{
  val digest=MessageDigest.getInstance("SHA-256")
  return digest.digest(components.joinToString("\u0000").toByteArray()).joinToString(""){"%02x".format(it)}
 }
 fun putManifest(vmId:String,fingerprint:String,metadata:String){
  val vmRoot=File(root,vmId).apply{mkdirs()}; File(vmRoot,"$fingerprint.meta").writeText(metadata)
 }
 fun has(vmId:String,fingerprint:String):Boolean=File(root,vmId).resolve("$fingerprint.meta").isFile
 fun stats(vmId:String?=null):TranslationCacheStats{
  val base=if(vmId==null)root else File(root,vmId)
  if(!base.exists())return TranslationCacheStats(0,0)
  val files=base.walkTopDown().filter{it.isFile}.toList()
  return TranslationCacheStats(files.size,files.sumOf{it.length()})
 }
 fun clear(vmId:String?=null){if(vmId==null)root.listFiles()?.forEach{it.deleteRecursively()}else File(root,vmId).deleteRecursively()}
}
