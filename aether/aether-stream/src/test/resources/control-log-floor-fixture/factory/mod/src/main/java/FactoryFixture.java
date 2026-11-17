// Fixture for ControlLogFloorTripwireTest: one construction in code, one in a comment, one declaration-shaped line.
class FactoryFixture {
    // ControlLog.controlLog(partitionLog, admissions, mirror) in a comment is not a construction
    static ControlLog controlLog(int declaration) { return null; }

    Object build() {
        return ControlLog.controlLog(partitionLog, admissions, mirror);
    }
}
