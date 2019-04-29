// !WITH_NEW_INFERENCE
// NI_EXPECTED_FILE

interface In<in E>
class A : In<A>
class B : In<B>
fun <T> select(x: T, y: T) = x ?: y

// Return type should be In<*> nor In<out Any?>
fun <!NI;IMPLICIT_INTERSECTION_TYPE!>foobar<!>(a: A, b: B) = select(a, b)
