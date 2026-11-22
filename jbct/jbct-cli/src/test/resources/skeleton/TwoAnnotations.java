package fixtures;

class TwoAnnotations {
    @Deprecated
    @SuppressWarnings("unused")
    public static
    void twoLines(int a,
                  int b) {
    }

    @Override
    @SafeVarargs
    final String single(String... parts) { return ""; }
}
