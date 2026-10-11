import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess

private val CAPTURES: Map<String, (vendorData: java.nio.file.Path) -> Unit> = mapOf(
    "duckdb" to ::captureDuckdb,
    "trino-keywords" to ::captureTrinoKeywords,
    "doris-keywords" to ::captureDorisKeywords,
)

fun main(args: Array<String>) {
    val capture = CAPTURES[args.singleOrNull()] ?: run {
        System.err.println("usage: metadata-capture <${CAPTURES.keys.joinToString("|")}>")
        exitProcess(2)
    }
    val vendorData = Path("vendor/data")
    require(vendorData.isDirectory()) { "run from the project root: no vendor/data in ${Path("").toAbsolutePath()}" }
    capture(vendorData)
}
