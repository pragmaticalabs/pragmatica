/// Selftest fixture: two TLA+ modules in one file whose operator names collide by prefix (Adopt in both,
/// RabiaAdopt beside it). `selftest.sh Alpha tools/tla/fixtures/two-modules` must pass: each probe edits only the
/// target module's fragment, never the other module's operator of the same name.
///
/// ```tla Alpha:head
/// EXTENDS Naturals
/// VARIABLE x
/// Init == x = 0
/// Inv == x \in Nat
/// ```
/// ```tla Alpha:Adopt
/// Adopt == x' = x + 1
/// ```
/// ```tla Alpha:tail
/// Next == Adopt
/// Spec == Init /\ [][Next]_x
/// ```
/// ```tlacfg Alpha:common
/// SPECIFICATION Spec
/// ```
/// ```tlacfg Alpha:run
/// INVARIANT Inv
/// ```
///
/// ```tla Beta:head
/// EXTENDS Naturals
/// VARIABLE y
/// Init == y = 0
/// Inv == y \in Nat
/// ```
/// ```tla Beta:Adopt
/// Adopt == y' = y + 1
/// ```
/// ```tla Beta:RabiaAdopt
/// RabiaAdopt == y' = y + 2
/// ```
/// ```tla Beta:tail
/// Next == Adopt \/ RabiaAdopt
/// Spec == Init /\ [][Next]_y
/// ```
/// ```tlacfg Beta:common
/// SPECIFICATION Spec
/// ```
/// ```tlacfg Beta:run
/// INVARIANT Inv
/// ```
final class TwoModules {}
