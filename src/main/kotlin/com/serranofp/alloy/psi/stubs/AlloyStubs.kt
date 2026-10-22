package com.serranofp.alloy.psi.stubs

import com.intellij.psi.StubBuilder
import com.intellij.psi.stubs.DefaultStubBuilder
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubBase
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.psi.tree.IStubFileElementType
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.AlloyTypes
import com.serranofp.alloy.psi.impl.AlloyAssertDeclImpl
import com.serranofp.alloy.psi.impl.AlloyCommandDeclImpl
import com.serranofp.alloy.psi.impl.AlloyFactDeclImpl
import com.serranofp.alloy.psi.impl.AlloyEnumDeclImpl
import com.serranofp.alloy.psi.impl.AlloyEnumMemberImpl
import com.serranofp.alloy.psi.impl.AlloyFieldDeclImpl
import com.serranofp.alloy.psi.impl.AlloyFunDeclImpl
import com.serranofp.alloy.psi.impl.AlloyMacroDeclImpl
import com.serranofp.alloy.psi.impl.AlloyPredDeclImpl
import com.serranofp.alloy.psi.impl.AlloySigNameImpl

internal val NAME_INDEX_KEY: StubIndexKey<String, AlloyNamedDeclaration> = StubIndexKey.createIndexKey("alloy.name")

/** The stub of a declaration in [AlloyNameIndex]: its name, or null for an unnamed assertion. */
class AlloyNamedStub(parent: StubElement<*>?, type: IStubElementType<*, *>, val name: String?) :
    StubBase<AlloyNamedDeclaration>(parent, type)

class AlloyNamedStubElementType(private val id: String) : IStubElementType<AlloyNamedStub, AlloyNamedDeclaration>(id, AlloyLanguage) {
    override fun getExternalId(): String = "alloy.$id"

    override fun createStub(psi: AlloyNamedDeclaration, parentStub: StubElement<out com.intellij.psi.PsiElement>?): AlloyNamedStub =
        AlloyNamedStub(parentStub, this, psi.name)

    // By the constants rather than by their names, so that renaming a rule in the grammar does not compile
    override fun createPsi(stub: AlloyNamedStub): AlloyNamedDeclaration = when (this) {
        AlloyTypes.SIG_NAME -> AlloySigNameImpl(stub, this)
        AlloyTypes.FIELD_DECL -> AlloyFieldDeclImpl(stub, this)
        AlloyTypes.ENUM_DECL -> AlloyEnumDeclImpl(stub, this)
        AlloyTypes.ENUM_MEMBER -> AlloyEnumMemberImpl(stub, this)
        AlloyTypes.PRED_DECL -> AlloyPredDeclImpl(stub, this)
        AlloyTypes.FUN_DECL -> AlloyFunDeclImpl(stub, this)
        AlloyTypes.ASSERT_DECL -> AlloyAssertDeclImpl(stub, this)
        AlloyTypes.MACRO_DECL -> AlloyMacroDeclImpl(stub, this)
        AlloyTypes.FACT_DECL -> AlloyFactDeclImpl(stub, this)
        AlloyTypes.COMMAND_DECL -> AlloyCommandDeclImpl(stub, this)
        else -> error("No stub-based PSI for $id")
    }

    override fun serialize(stub: AlloyNamedStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): AlloyNamedStub =
        AlloyNamedStub(parentStub, this, dataStream.readNameString())

    override fun indexStub(stub: AlloyNamedStub, sink: IndexSink) {
        stub.name?.let { sink.occurrence(NAME_INDEX_KEY, it) }
    }
}

/** Referenced by the generated `AlloyTypes` (`elementTypeFactory` in `Alloy.bnf`). */
object AlloyStubElementTypes {
    @JvmStatic
    fun create(debugName: String): IStubElementType<*, *> = AlloyNamedStubElementType(debugName)
}

object AlloyFileElementType : IStubFileElementType<PsiFileStub<AlloyFile>>("ALLOY_FILE", AlloyLanguage) {
    override fun getStubVersion(): Int = 3

    override fun getExternalId(): String = "alloy.FILE"

    override fun getBuilder(): StubBuilder = DefaultStubBuilder()
}

/**
 * Top-level declarations and fields of every Alloy file in the project, by name: signatures, fields, enums and
 * their members, predicates, functions, macros, and named assertions, facts and commands. Parameters and binders
 * are not in it.
 */
class AlloyNameIndex : StringStubIndexExtension<AlloyNamedDeclaration>() {
    override fun getKey(): StubIndexKey<String, AlloyNamedDeclaration> = NAME_INDEX_KEY

    override fun getVersion(): Int = super.getVersion() + 1
}

