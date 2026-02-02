package org.pragmatica.aether.artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupIdTest {

    @Test
    void groupId_with_valid_domain_format_succeeds() {
        GroupId.groupId("com.example")
               .onSuccess(groupId -> {
                   assertThat(groupId.id()).isEqualTo("com.example");
                   assertThat(groupId.toString()).isEqualTo("com.example");
               })
               .onFailureRun(Assertions::fail);
    }

    @Test
    void groupId_with_long_domain_format_succeeds() {
        GroupId.groupId("org.springframework.boot")
               .onSuccess(groupId -> assertThat(groupId.id()).isEqualTo("org.springframework.boot"))
               .onFailureRun(Assertions::fail);
    }

    @Test
    void groupId_with_numbers_succeeds() {
        GroupId.groupId("com.example2.test3")
               .onSuccess(groupId -> assertThat(groupId.id()).isEqualTo("com.example2.test3"))
               .onFailureRun(Assertions::fail);
    }

    @Test
    void groupId_with_underscores_succeeds() {
        GroupId.groupId("com.example_company.my_project")
               .onSuccess(groupId -> assertThat(groupId.id()).isEqualTo("com.example_company.my_project"))
               .onFailureRun(Assertions::fail);
    }

    @Test
    void groupId_with_mixed_format_succeeds() {
        GroupId.groupId("org.pragmatica_lite.aether")
               .onSuccess(groupId -> assertThat(groupId.id()).isEqualTo("org.pragmatica_lite.aether"))
               .onFailureRun(Assertions::fail);
    }

    @Test
    void groupId_with_minimal_valid_format_succeeds() {
        GroupId.groupId("a.b")
               .onSuccess(groupId -> assertThat(groupId.id()).isEqualTo("a.b"))
               .onFailureRun(Assertions::fail);
    }

    @Test
    void groupId_with_single_segment_fails() {
        GroupId.groupId("example")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void groupId_with_uppercase_letters_fails() {
        GroupId.groupId("Com.Example")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void groupId_starting_with_number_fails() {
        GroupId.groupId("1com.example")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void groupId_segment_starting_with_number_fails() {
        GroupId.groupId("com.1example")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void groupId_with_hyphen_succeeds() {
        GroupId.groupId("com.example-company")
               .onSuccess(groupId -> assertThat(groupId.id()).isEqualTo("com.example-company"))
               .onFailureRun(Assertions::fail);
    }

    @Test
    void groupId_with_leading_dot_fails() {
        GroupId.groupId(".com.example")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void groupId_with_trailing_dot_fails() {
        GroupId.groupId("com.example.")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void groupId_with_double_dot_fails() {
        GroupId.groupId("com..example")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void groupId_with_spaces_fails() {
        GroupId.groupId("com example")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void groupId_with_special_characters_fails() {
        GroupId.groupId("com.example@test")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void groupId_with_empty_string_fails() {
        GroupId.groupId("")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void groupId_with_empty_segment_fails() {
        GroupId.groupId("com..example")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void groupId_equality_works() {
        GroupId.groupId("com.test")
               .onSuccess(groupId1 ->
                                  GroupId.groupId("com.test")
                                         .onSuccess(groupId2 -> {
                                             assertThat(groupId1).isEqualTo(groupId2);
                                             assertThat(groupId1.hashCode()).isEqualTo(groupId2.hashCode());
                                         })
                                         .onFailureRun(Assertions::fail))
               .onFailureRun(Assertions::fail);
    }

    @Test
    void groupId_inequality_works() {
        GroupId.groupId("com.test1")
               .onSuccess(groupId1 ->
                                  GroupId.groupId("com.test2")
                                         .onSuccess(groupId2 -> assertThat(groupId1).isNotEqualTo(groupId2))
                                         .onFailureRun(Assertions::fail))
               .onFailureRun(Assertions::fail);
    }
}