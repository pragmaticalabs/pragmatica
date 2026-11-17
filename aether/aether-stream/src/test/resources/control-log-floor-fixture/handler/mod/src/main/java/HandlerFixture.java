// Fixture for ControlLogFloorTripwireTest: one 4-argument (ControlLog) call whose last argument carries a generic comma,
// one 7-argument call, one comment, one declaration. Exactly one of them is a hit.
class HandlerFixture {
    // StreamForwardHandler.streamForwardHandler(self, manager, transport, controlLog) in a comment does not count
    static StreamForwardHandler streamForwardHandler(NodeId self, int x) { return null; }

    Object build() {
        var sevenArguments = StreamForwardHandler.streamForwardHandler(self, manager, transport, 1L, metrics, Option.none(), Option.none());
        return StreamForwardHandler.streamForwardHandler(self, manager, transport, Holder.<Map<String, X>, Y>get());
    }
}
