// The evaluation harness: the only module that sees both the world and the product.
dependencies {
    "implementation"(project(":common"))
    "implementation"(project(":product"))
    "implementation"(project(":world"))
}

fun runEval(name: String, vararg arguments: String) = tasks.register<JavaExec>(name) {
    group = "evaluation"
    classpath = project.the<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("cashback.eval.Main")
    args(*arguments)
    workingDir = rootDir
}

runEval("evaluate", "evaluate")
runEval("gate", "gate")
runEval("readme", "readme")
runEval("heldout", "heldout")
runEval("seed", "seed")
