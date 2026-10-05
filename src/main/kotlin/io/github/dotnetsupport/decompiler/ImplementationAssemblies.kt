package io.github.dotnetsupport.decompiler

import java.io.File

/**
 * What a project compiles against is often a reference assembly: the packs of the SDK (`packs/Microsoft.NETCore.App.Ref/10.0.12/ref/net10.0`),
 * the `ref/` folder of a package, the reference assemblies of .NET Framework. Their methods have no bodies (`throw null`), so, as Rider
 * does, the decompiler is given the assembly that runs instead — the shared framework of the same version, `lib/` of the same package, the
 * Framework folder of Windows — and the XML documentation of the reference one, which the implementation has not next to it.
 */
object ImplementationAssemblies {
    /** The assembly to decompile and the XML documentation for it. */
    data class Target(val assembly: File, val xmlDoc: File?)

    fun forReference(reference: File, dotnetRoot: File? = null, windowsDirectory: File? = defaultWindowsDirectory()): Target {
        val xml = File(reference.parentFile, reference.nameWithoutExtension + ".xml").takeIf { it.isFile }
        val implementation = sharedFramework(reference, dotnetRoot) ?: packageLib(reference) ?: netFramework(reference, windowsDirectory)
        return if (implementation == null) Target(reference, null) else Target(implementation, xml)
    }

    /** `<root>/packs/<Name>.Ref/<version>/ref/<tfm>/X.dll` -> `<root>/shared/<Name>/<version>/X.dll`, else the newest patch of that major.minor. */
    fun sharedFramework(reference: File, dotnetRoot: File?): File? {
        val tfm = reference.parentFile ?: return null
        val ref = tfm.parentFile?.takeIf { it.name.equals("ref", ignoreCase = true) } ?: return null
        val version = ref.parentFile ?: return null
        val pack = version.parentFile?.takeIf { it.name.endsWith(".Ref", ignoreCase = true) } ?: return null
        val root = dotnetRoot ?: pack.parentFile?.takeIf { it.name.equals("packs", ignoreCase = true) }?.parentFile ?: return null
        val shared = File(root, "shared/${pack.name.dropLast(".Ref".length)}")
        File(shared, "${version.name}/${reference.name}").takeIf { it.isFile }?.let { return it }
        val majorMinor = version.name.split('.').take(2).joinToString(".")
        return shared.listFiles { f -> f.isDirectory && (f.name == majorMinor || f.name.startsWith("$majorMinor.")) && File(f, reference.name).isFile }
            ?.maxWithOrNull(compareBy({ patch(it.name) }, { it.name }))?.let { File(it, reference.name) }
    }

    /** `<package>/<version>/ref/<tfm>/X.dll` -> `<package>/<version>/lib/<tfm>/X.dll` when the package has it. */
    fun packageLib(reference: File): File? {
        val tfm = reference.parentFile ?: return null
        val ref = tfm.parentFile?.takeIf { it.name.equals("ref", ignoreCase = true) } ?: return null
        val lib = File(ref.parentFile ?: return null, "lib/${tfm.name}/${reference.name}")
        return lib.takeIf { it.isFile }
    }

    /** `Reference Assemblies/Microsoft/Framework/.NETFramework/v4.7.2/X.dll` -> `%WINDIR%/Microsoft.NET/Framework64/v4.0.30319/X.dll`. */
    fun netFramework(reference: File, windowsDirectory: File?): File? {
        val version = reference.parentFile?.let { if (it.name.equals("Facades", ignoreCase = true)) it.parentFile else it } ?: return null
        if (version.parentFile?.name?.equals(".NETFramework", ignoreCase = true) != true || !version.name.startsWith("v4")) return null
        val windows = windowsDirectory ?: return null
        return listOf("Framework64", "Framework").map { File(windows, "Microsoft.NET/$it/v4.0.30319/${reference.name}") }.firstOrNull { it.isFile }
    }

    private fun patch(version: String): Int = version.split('.').getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    private fun defaultWindowsDirectory(): File? = System.getenv("WINDIR")?.let(::File)?.takeIf { it.isDirectory }
}
