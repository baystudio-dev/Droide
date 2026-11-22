package com.baystudio.droide.core
import java.io.File
import java.io.Reader
import java.io.StringReader
import java.nio.file.Files
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class DroideHandbookGrowthTest {
 private fun guide():String=DroideHandbookPolicy.sections.entries.joinToString("\n"){(name,number)->"## "+number+". "+name+"\n"+name.repeat(800).take(3500)}
 @Test fun guideBeyondOldWholeDocumentCapStillReturnsOnlyRequestedSection() {
  val doc=guide();assertTrue(doc.length>32000)
  for((name,number) in DroideHandbookPolicy.sections) {
   val expected="## "+number+". "+name+"\n"+name.repeat(800).take(3500)
   assertEquals(expected,DroideHandbookPolicy.readSection(StringReader(doc),name))
  }
 }
 private fun bundledGuide(): String {
  val root = File(System.getProperty("droide.source", "."))
  val relative = "src/main/assets/" + DroideHandbookPolicy.ASSET
  return root.resolve("app/$relative").takeIf { it.isFile }?.readText() ?: File(relative).readText()
 }
 @Test fun smallUnrelatedEditDoesNotDisableAllCurrentSections() {
  val doc = bundledGuide()
  val grown=doc+"\n"+" ".repeat(200)
  assertTrue(grown.length>32000)
  for(name in DroideHandbookPolicy.sections.keys)
   assertEquals(DroideHandbookPolicy.readSection(StringReader(doc),name),DroideHandbookPolicy.readSection(StringReader(grown),name))
 }
 @Test fun scanBudgetTracksAllowlistedSectionsWithoutChangingResponseBudgets() {
  assertEquals((DroideHandbookPolicy.sections.size+1)*5000,DroideHandbookPolicy.MAX_DOCUMENT_CHARS)
  assertEquals(5000,DroideHandbookPolicy.MAX_SECTION_CHARS)
  assertEquals(6500,DroideHandbookPolicy.MAX_RESULT_CHARS)
  assertEquals(8000,DroideHandbookPolicy.MAX_GROUP_CHARS)
 }
 @Test fun exactDocumentGuardAndOneCharacterOverflowRemainExplicit() {
  val head="## 1. Identity\ntext"
  val exact=head+" ".repeat(DroideHandbookPolicy.MAX_DOCUMENT_CHARS-head.length)
  assertEquals(head,DroideHandbookPolicy.readSection(StringReader(exact),"identity"))
  val failure=assertThrows(IllegalArgumentException::class.java){DroideHandbookPolicy.readSection(StringReader(exact+" "),"identity")}
  assertTrue(failure.message!!.contains("read limit"))
 }
 @Test fun selectedSectionCapAndTrimRemainUnchanged() {
  val head="## 1. Identity\n";val exact=head+"x".repeat(5000-head.length)
  assertEquals(exact,DroideHandbookPolicy.readSection(StringReader(exact+"  \r\n"),"identity"))
  assertThrows(IllegalArgumentException::class.java){DroideHandbookPolicy.readSection(StringReader(exact+"x"),"identity")}
 }
 @Test fun longUnselectedSectionDoesNotBecomeRequestedText() {
  val doc="## 0. Index\n"+"x".repeat(34000)+"\n## 1. Identity\nSelected"
  assertEquals("## 1. Identity\nSelected",DroideHandbookPolicy.readSection(StringReader(doc),"identity"))
 }
 @Test fun lateDuplicateStillFailsInsteadOfReturningFirstMatch() {
  val doc=guide()+"\n## 1. Duplicate\nAgain"
  val failure=assertThrows(IllegalArgumentException::class.java){DroideHandbookPolicy.readSection(StringReader(doc),"identity")}
  assertTrue(failure.message!!.contains("ambiguous"))
 }
 @Test fun externalIdeHelpUsesTheSameBoundedSectionReader() = runBlocking {
  val root=Files.createTempDirectory("handbook-external").toFile()
  try {
   val lsp=Proxy.newProxyInstance(LspManager::class.java.classLoader,arrayOf(LspManager::class.java)){_,_,_->throw AssertionError("No LSP call")} as LspManager
   val doc = bundledGuide()
   val provider=ExternalIdeTools(FileRepository(root),null,lsp,PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW))),ApprovalManager(),requireOwner={},handbook={doc})
   try {
    val definition=provider.definitions.map { it.jsonObject }.single { it["name"]?.jsonPrimitive?.content=="droide_help" }
    val choices=definition.getValue("inputSchema").jsonObject.getValue("properties").jsonObject
     .getValue("section").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()
    assertEquals(DroideHandbookPolicy.sections.keys,choices)
    for (section in DroideHandbookPolicy.sections.keys) {
     val result = provider.call("droide_help", buildJsonObject { put("section", section) })
     val expected = DroideHandbookPolicy.readSection(StringReader(doc), section)
     assertTrue(result, result.endsWith(expected))
     assertEquals(1,Regex("(?m)^## ").findAll(result).count())
     assertTrue(result.length<DroideHandbookPolicy.MAX_RESULT_CHARS)
    }
   } finally {provider.close()}
  } finally {PathSecurity.deleteTreeNoFollow(root)}
 }
 @Test fun chunkedCrlfAndUnicodePreserveExactSelectedBytes() {
  val doc="preamble\r\n## 1. Identity\r\nUnicode 😀 text\r\n## 99. Unknown\r\nOther"
  val reader=object:Reader(){var offset=0;override fun close(){};override fun read(buffer:CharArray,start:Int,count:Int):Int {
   if(offset==doc.length)return -1
   buffer[start]=doc[offset++];return 1
  }}
  assertEquals("## 1. Identity\r\nUnicode 😀 text",DroideHandbookPolicy.readSection(reader,"identity"))
 }
}
