package fixtures;

import java.util.function.Supplier;

class Anonymous {
    Runnable build(String name) {
        class Local {
            void run() {}
        }
        Supplier<String> supplier = () -> {
            return name;
        };
        return new Runnable() {
            @Override
            public void run() {
                System.out.println(supplier.get());
            }

            int hidden;
        };
    }

    static final Runnable FIELD = new Runnable() {
        public void run() {}
    };

    void after() {}
}
