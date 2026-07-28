plugins {
    alias(libs.plugins.convention.bukkit)
    alias(libs.plugins.resourcefactory.bukkit)
}

dependencies {
    compileOnly(project(":bettermodel-api"))
    compileOnly(project(":bettermodel-api:bettermodel-bukkit-api"))
}

val pluginName = "BetterModel-Benchmark"

tasks.jar {
    archiveBaseName = pluginName
}

bukkitPluginYaml {
    main = "$group.benchmark.BetterModelBenchmark"
    version = project.version.toString()
    name = pluginName
    foliaSupported = false
    apiVersion = "1.20"
    author = "toxicity"
    description = "BetterModel's performance benchmark plugin"
    depend = listOf(
        "BetterModel"
    )
    commands.register("bmbench") {
        usage = "/<command> <spawn|clear|measure|status>"
        description = "BetterModel benchmark controls."
        permission = "bettermodel.benchmark"
    }
}
