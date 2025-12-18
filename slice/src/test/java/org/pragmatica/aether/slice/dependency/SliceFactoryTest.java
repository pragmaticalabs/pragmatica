package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SliceFactoryTest {

    // Test slice with no dependencies
    public static class SimpleSlice implements Slice {
        public static SimpleSlice simpleSlice() {
            return new SimpleSlice();
        }

        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    // Test slice with dependencies
    public static class OrderService implements Slice {
        private final UserService userService;
        private final EmailService emailService;

        private OrderService(UserService userService, EmailService emailService) {
            this.userService = userService;
            this.emailService = emailService;
        }

        public static OrderService orderService(UserService userService, EmailService emailService) {
            return new OrderService(userService, emailService);
        }

        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }

        public UserService getUserService() {
            return userService;
        }

        public EmailService getEmailService() {
            return emailService;
        }
    }

    public static class UserService implements Slice {
        public static UserService userService() {
            return new UserService();
        }

        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    public static class EmailService implements Slice {
        public static EmailService emailService() {
            return new EmailService();
        }

        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    @Test
    void creates_slice_with_no_dependencies() {
        SliceFactory.createSlice(SimpleSlice.class, List.of(), List.of())
                    .onFailureRun(Assertions::fail)
                    .onSuccess(slice -> {
                        assertThat(slice).isInstanceOf(SimpleSlice.class);
                    });
    }

    @Test
    void creates_slice_with_dependencies() {
        var userService = new UserService();
        var emailService = new EmailService();

        var userDescriptor = DependencyDescriptor.parse("org.example.UserService:1.0.0:userService").unwrap();
        var emailDescriptor = DependencyDescriptor.parse("org.example.EmailService:1.0.0:emailService").unwrap();

        SliceFactory.createSlice(
                            OrderService.class,
                            List.of(userService, emailService),
                            List.of(userDescriptor, emailDescriptor)
                                )
                    .onFailureRun(Assertions::fail)
                    .onSuccess(slice -> {
                        assertThat(slice).isInstanceOf(OrderService.class);
                        var orderService = (OrderService) slice;
                        assertThat(orderService.getUserService()).isSameAs(userService);
                        assertThat(orderService.getEmailService()).isSameAs(emailService);
                    });
    }

    @Test
    void fails_when_factory_method_not_found() {
        // NoFactory doesn't have a static noFactory() method
        class NoFactory implements Slice {
            @Override
            public List<SliceMethod<?, ?>> methods() {
                return List.of();
            }
        }

        SliceFactory.createSlice(NoFactory.class, List.of(), List.of())
                    .onSuccessRun(() -> Assertions.fail("Should fail when factory method not found"))
                    .onFailure(cause -> {
                        assertThat(cause.message()).contains("Factory method not found");
                        assertThat(cause.message()).contains("noFactory");
                    });
    }

    @Test
    void fails_when_parameter_count_mismatch() {
        var userService = new UserService();

        var userDescriptor = DependencyDescriptor.parse("org.example.UserService:1.0.0").unwrap();

        // OrderService expects 2 parameters, but we provide only 1
        SliceFactory.createSlice(
                            OrderService.class,
                            List.of(userService),
                            List.of(userDescriptor)
                                )
                    .onSuccessRun(() -> Assertions.fail("Should fail on parameter count mismatch"))
                    .onFailure(cause -> {
                        assertThat(cause.message()).contains("Parameter count mismatch");
                        assertThat(cause.message()).contains("expected 2");
                        assertThat(cause.message()).contains("got 1");
                    });
    }

    @Test
    void fails_when_parameter_type_mismatch() {
        var emailService = new EmailService();  // Wrong type for first parameter

        var emailDescriptor = DependencyDescriptor.parse("org.example.EmailService:1.0.0").unwrap();
        var userDescriptor = DependencyDescriptor.parse("org.example.UserService:1.0.0").unwrap();

        // OrderService expects (UserService, EmailService), but we provide (EmailService, EmailService)
        SliceFactory.createSlice(
                            OrderService.class,
                            List.of(emailService, emailService),
                            List.of(emailDescriptor, userDescriptor)
                                )
                    .onSuccessRun(() -> Assertions.fail("Should fail on parameter type mismatch"))
                    .onFailure(cause -> {
                        assertThat(cause.message()).contains("Parameter type mismatch");
                        assertThat(cause.message()).contains("index 0");
                    });
    }
}
