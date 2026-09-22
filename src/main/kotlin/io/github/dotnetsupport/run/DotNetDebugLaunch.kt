package io.github.dotnetsupport.run

import com.intellij.openapi.util.Key
import com.intellij.util.execution.ParametersListUtil
import java.io.File

/**
 * The `launch` request of a debug adapter for a ".NET Project" configuration. Under a debugger it is the adapter that starts the program,
 * not `dotnet run`, so what the CLI does on its own (the launch profile, the hosting environment) is repeated here.
 * Knows nothing about the DAP client: a map in, a map out.
 */
object DotNetLaunchArguments {
    /** The assembly the build before the launch has produced, left in the execution environment for whoever starts the debugger. */
    val TARGET_PATH: Key<String> = Key.create("io.github.dotnetsupport.debug.targetPath")

    /** Set by the build before the launch. Absent for a configuration without that task (every one made before the task existed). */
    val BUILT: Key<Boolean> = Key.create("io.github.dotnetsupport.debug.built")

    /**
     * Without the path of the assembly the adapter is given the project and finds (and builds) the output itself, see [setProgram].
     * The environment follows `dotnet run`: the variables of the profile win over the ones of the configuration, the chosen environment name
     * (`-e` of the CLI) wins over the profile unless the table of the configuration names the variable itself.
     */
    fun build(
        projectPath: String, programArguments: List<String>, workingDirectory: String?, environment: Map<String, String>, environmentName: String?,
        profile: LaunchSettings.Profile?, configuration: String? = null, justMyCode: Boolean = true, allowImplicitEvaluation: Boolean = true,
    ): Map<String, Any> {
        val hosting = environmentName?.trim().orEmpty().takeIf { it.isNotEmpty() }
            ?.let { name -> DotNetRunConfiguration.HOSTING_VARIABLES.filter { it !in environment }.associateWith { name } }.orEmpty()
        val variables = LinkedHashMap(environment + profile?.environmentVariables.orEmpty() + hosting)
        // `applicationUrl` is what `dotnet run` turns into ASPNETCORE_URLS
        profile?.applicationUrls?.takeIf { it.isNotEmpty() }?.let { variables.putIfAbsent("ASPNETCORE_URLS", it.joinToString(";")) }

        return linkedMapOf<String, Any>(
            "project" to projectPath,
            "build" to true,
            // the profile is applied above, by the rules of `dotnet run`; the adapter would apply the default one on its own
            "launchSettingsProfile" to "",
            // the arguments of the profile are for a launch that has none of its own, as in the CLI
            "args" to programArguments.ifEmpty { ParametersListUtil.parse(profile?.commandLineArgs.orEmpty()) },
            "cwd" to (workingDirectory?.takeIf { it.isNotBlank() } ?: File(projectPath).parent.orEmpty()),
            "env" to variables,
            "justMyCode" to justMyCode,
            // off: values are described without running the code of the program (ToString(), getters, [DebuggerDisplay])
            "allowImplicitFuncEval" to allowImplicitEvaluation,
            // the adapter asks the plugin to start the program (runInTerminal): then the debug console can give it input
            "console" to "integratedTerminal",
        ).apply { if (!configuration.isNullOrBlank()) put("configuration", configuration) } // for the adapter that looks for the output itself
    }

    /** The `attach` request: the same preferences as a launch, and the process instead of the program. */
    fun attach(processId: Long, justMyCode: Boolean = true, allowImplicitEvaluation: Boolean = true): Map<String, Any> =
        linkedMapOf("processId" to processId, "justMyCode" to justMyCode, "allowImplicitFuncEval" to allowImplicitEvaluation)

    /**
     * The project is already built: the adapter builds nothing, and starts [targetPath] when the output is known (otherwise it still
     * finds the output of the project itself).
     */
    fun setProgram(arguments: MutableMap<String, Any?>, targetPath: String?) {
        if (targetPath == null) return
        arguments.remove("build")
        if (targetPath.isNotBlank()) {
            arguments.remove("project")
            arguments["program"] = targetPath
        }
    }
}

/** `dotnet msbuild -getProperty:TargetPath`: where the build puts the assembly of a project. */
object MsBuildTargetPath {
    /** [framework] is a must for a project with several of them: the outer build of such a project has no TargetPath. */
    fun arguments(projectPath: String, configuration: String, framework: String?, properties: List<String> = emptyList()): List<String> =
        listOf("msbuild", projectPath, "-nologo", "-getProperty:TargetPath", "-p:Configuration=$configuration") +
            listOfNotNull(framework?.let { "-p:TargetFramework=$it" }) + properties

    /** A single property is printed as is; anything else on the output (a warning of the SDK) goes before it. */
    fun parse(output: String): String? = output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        ?.takeIf { it.endsWith(".dll", ignoreCase = true) || it.endsWith(".exe", ignoreCase = true) }
}
