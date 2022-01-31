package it.unibo.vocalization.modules

import it.unibo.conversational.database.Config
import it.unibo.conversational.olap.Operator
import it.unibo.vocalization.modules.TopK.topKpatterns
import krangl.DataFrame
import krangl.readCSV
import krangl.writeCSV
import java.io.File
import java.util.*

/**
 * Describe intention in action.
 */
object Skyline : VocalizationModule {
    override val moduleName: String
        get() = "skyline"

    val fileName = "${UUID.randomUUID()}.csv"
    override fun compute(cube1: IGPSJ?, cube2: IGPSJ, operator: Operator?): List<IVocalizationPattern> {
        val cube: IGPSJ = cube2
        val path = "generated/"

        cube.df.writeCSV(File("$path$fileName"))
        computePython(Config.getPython(), path, "modules.py", cube.measureNames())

        cube.df = DataFrame.readCSV(File("$path$fileName"))
        return topKpatterns(moduleName, cube, "dominance").filter { it.int > 0 }
    }

    override fun applyCondition(cube1: IGPSJ?, cube2: IGPSJ, operator: Operator?): Boolean {
        return cube2.measures.size > 1
    }

    override fun toPythonCommand(commandPath: String, path: String, measures: Collection<String>): String {
        val fullCommand = (commandPath.replace("/", File.separator) //
                + " --path " + (if (path.contains(" ")) "\"" else "") + path.replace("\\", "/") + (if (path.contains(" ")) "\"" else "") //
                + " --file $fileName" //
                + " --module $moduleName"
                + " --measures ${measures.reduce{ a, b -> "$a,$b" }}")
        return fullCommand
    }
}