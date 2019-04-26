// PROBLEM: none
// WITH_RUNTIME
// ERROR: No value passed for parameter 'second'
// ERROR: Not enough information to infer type variable B
import kotlin.Pair
fun test() {
    val p = <caret>Pair(1, )
}