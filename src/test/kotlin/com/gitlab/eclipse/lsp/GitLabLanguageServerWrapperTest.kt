package com.gitlab.eclipse.lsp

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

private const val CLASS_FILE_MAGIC = 0xCAFEBABE.toInt()

private const val CONSTANT_UTF8 = 1
private const val CONSTANT_INTEGER = 3
private const val CONSTANT_FLOAT = 4
private const val CONSTANT_LONG = 5
private const val CONSTANT_DOUBLE = 6
private const val CONSTANT_CLASS = 7
private const val CONSTANT_STRING = 8
private const val CONSTANT_FIELD_REF = 9
private const val CONSTANT_METHOD_REF = 10
private const val CONSTANT_INTERFACE_METHOD_REF = 11
private const val CONSTANT_NAME_AND_TYPE = 12
private const val CONSTANT_METHOD_HANDLE = 15
private const val CONSTANT_METHOD_TYPE = 16
private const val CONSTANT_DYNAMIC = 17
private const val CONSTANT_INVOKE_DYNAMIC = 18
private const val CONSTANT_MODULE = 19
private const val CONSTANT_PACKAGE = 20

private const val INDEX_PAYLOAD = 2L
private const val METHOD_HANDLE_PAYLOAD = 3L
private const val PAIR_PAYLOAD = 4L
private const val WIDE_PAYLOAD = 8L

/** How many bytes follow the tag, for every constant this reader walks past without looking. */
private val SKIPPED_PAYLOAD_BYTES = mapOf(
  CONSTANT_INTEGER to PAIR_PAYLOAD,
  CONSTANT_FLOAT to PAIR_PAYLOAD,
  CONSTANT_FIELD_REF to PAIR_PAYLOAD,
  CONSTANT_DYNAMIC to PAIR_PAYLOAD,
  CONSTANT_INVOKE_DYNAMIC to PAIR_PAYLOAD,
  CONSTANT_LONG to WIDE_PAYLOAD,
  CONSTANT_DOUBLE to WIDE_PAYLOAD,
  CONSTANT_STRING to INDEX_PAYLOAD,
  CONSTANT_METHOD_TYPE to INDEX_PAYLOAD,
  CONSTANT_MODULE to INDEX_PAYLOAD,
  CONSTANT_PACKAGE to INDEX_PAYLOAD,
  CONSTANT_METHOD_HANDLE to METHOD_HANDLE_PAYLOAD,
)

/** The two constants that occupy two constant pool slots each. */
private val DOUBLE_WIDTH_TAGS = setOf(CONSTANT_LONG, CONSTANT_DOUBLE)

/**
 * Every method that [type]'s class file references, as `owner.name` — for example
 * `java/util/concurrent/atomic/AtomicReference.compareAndSet`.
 *
 * Read out of the constant pool, using nothing but the JDK, because reflection stops at signatures:
 * it cannot say which [AtomicReference] operation a body compiles down to. **The constant pool is
 * class scoped**, so the answer is "this class references that method somewhere in it", never
 * "this particular method of it does".
 */
private fun methodReferencesOf(type: Class<*>): Set<String> {
  val bytes = checkNotNull(
    type.classLoader.getResourceAsStream(type.name.replace('.', '/') + ".class")
  ) { "No class file on the classpath for ${type.name}" }.use { it.readBytes() }

  return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
    check(input.readInt() == CLASS_FILE_MAGIC) { "Not a class file: ${type.name}" }
    input.skipNBytes(PAIR_PAYLOAD) // minor and major version

    val text = mutableMapOf<Int, String>()
    val classNameIndex = mutableMapOf<Int, Int>()
    val memberNameIndex = mutableMapOf<Int, Int>()
    val methodRefs = mutableListOf<Pair<Int, Int>>()

    val entryCount = input.readUnsignedShort()
    var index = 1
    while (index < entryCount) {
      val tag = input.readUnsignedByte()
      when (tag) {
        // readUTF reads a u2 length followed by modified UTF-8 — exactly CONSTANT_Utf8's layout.
        CONSTANT_UTF8 -> text[index] = input.readUTF()
        CONSTANT_CLASS -> classNameIndex[index] = input.readUnsignedShort()
        CONSTANT_NAME_AND_TYPE -> {
          memberNameIndex[index] = input.readUnsignedShort()
          input.skipNBytes(INDEX_PAYLOAD) // descriptor index
        }
        CONSTANT_METHOD_REF, CONSTANT_INTERFACE_METHOD_REF ->
          methodRefs += input.readUnsignedShort() to input.readUnsignedShort()
        else -> input.skipNBytes(
          SKIPPED_PAYLOAD_BYTES[tag] ?: error("Unknown constant pool tag $tag in ${type.name}")
        )
      }
      index += if (tag in DOUBLE_WIDTH_TAGS) 2 else 1
    }

    methodRefs.mapNotNull { (classIndex, nameAndTypeIndex) ->
      val owner = text[classNameIndex[classIndex]] ?: return@mapNotNull null
      val name = text[memberNameIndex[nameAndTypeIndex]] ?: return@mapNotNull null
      "$owner.$name"
    }.toSet()
  }
}

class GitLabLanguageServerWrapperTest : DescribeSpec({
  val wrapper = GitLabLanguageServerWrapper()

  beforeEach {
    // The wrapper's state is process-wide (companion object), so every test starts from empty
    // rather than from whatever the previous one left behind.
    wrapper.unregisterLanguageServer()
  }

  describe("snapshot publication") {
    // Design §21 A25 (structural half).
    it("holds the connection handle in one final AtomicReference field and no other state") {
      val stateFields = GitLabLanguageServerWrapper::class.java.declaredFields
        .filterNot { it.isSynthetic }
        .filterNot { it.name == "Companion" }

      stateFields.map { it.type to Modifier.isFinal(it.modifiers) } shouldBe
        listOf(AtomicReference::class.java to true)
    }

    // Design §21 A25 (consistency half). Read through the production accessors only.
    it("derives languageServer from currentSnapshot at every register and unregister transition") {
      val proxyA = mockk<GitLabLanguageServer>()
      val handleA = LanguageServerHandle(proxyA, LanguageServerSession())
      val proxyB = mockk<GitLabLanguageServer>()
      val handleB = LanguageServerHandle(proxyB, LanguageServerSession())

      val observed = mutableListOf<Pair<GitLabLanguageServer?, GitLabLanguageServer?>>()
      fun observe() {
        observed += wrapper.languageServer to wrapper.currentSnapshot?.proxy
      }

      observe()
      wrapper.registerLanguageServer(handleA)
      observe()
      wrapper.registerLanguageServer(handleB)
      observe()
      wrapper.unregisterLanguageServer()
      observe()
      wrapper.registerLanguageServer(handleA)
      observe()

      observed shouldBe listOf(
        null to null,
        proxyA to proxyA,
        proxyB to proxyB,
        null to null,
        proxyA to proxyA,
      )
    }
  }

  describe("identity-aware revocation") {
    // Design §21 A28, atomicity half. No comparison of observed values can tell a compare-and-set
    // from a read-modify-write — the difference only shows in an interleaving that AtomicReference
    // gives no seam to force — so this is pinned structurally (§20a rule 3).
    //
    // What it fixes is class scoped: "this class references AtomicReference.compareAndSet", not
    // "the two-arg unregisterLanguageServer uses it". That is discriminating here because the
    // revocation is the class's only use of it, so rewriting it as a get-then-set removes the last
    // reference and this goes red. It would stop discriminating if another member of this class
    // ever called compareAndSet.
    it("compiles the revocation down to a reference to AtomicReference.compareAndSet") {
      methodReferencesOf(GitLabLanguageServerWrapper::class.java) shouldContain
        "java/util/concurrent/atomic/AtomicReference.compareAndSet"
    }

    // Design §21 A28, identity half.
    it("leaves the newer connection's snapshot alone when a superseded handle is revoked") {
      val handleA = LanguageServerHandle(mockk(), LanguageServerSession())
      val handleB = LanguageServerHandle(mockk(), LanguageServerSession())
      wrapper.registerLanguageServer(handleA)
      // A captured its own handle above; B takes over before A gets around to revoking.
      wrapper.registerLanguageServer(handleB)

      val revoked = wrapper.unregisterLanguageServer(handleA)

      (revoked to wrapper.currentSnapshot) shouldBe (false to handleB)
    }

    it("clears both accessors when the revoked handle is still the current one") {
      val handleA = LanguageServerHandle(mockk(), LanguageServerSession())
      wrapper.registerLanguageServer(handleA)

      val revoked = wrapper.unregisterLanguageServer(handleA)

      Triple(revoked, wrapper.currentSnapshot, wrapper.languageServer) shouldBe
        Triple(true, null, null)
    }
  }
})
