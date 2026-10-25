package com.apac.claims.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

    @Test
    void fromCopiesTheFiguresOfTheMiddlePage() {
        Page<String> page = new PageImpl<>(List.of("c", "d"), PageRequest.of(1, 2), 5);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.content()).containsExactly("c", "d");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.last()).isFalse();
    }

    @Test
    void theLastPageIsFlaggedAsLast() {
        Page<String> page = new PageImpl<>(List.of("e"), PageRequest.of(2, 2), 5);

        assertThat(PageResponse.from(page).last()).isTrue();
    }

    @Test
    void ofMapsEveryElementWithoutChangingThePaging() {
        Page<String> page = new PageImpl<>(List.of("a", "bb", "ccc"), PageRequest.of(0, 3), 7);

        PageResponse<Integer> response = PageResponse.of(page, String::length);

        assertThat(response.content()).containsExactly(1, 2, 3);
        assertThat(response.totalElements()).isEqualTo(7);
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void anEmptyPageIsAValidResponse() {
        PageResponse<String> response = PageResponse.from(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.last()).isTrue();
    }

    @Test
    void contentCannotBeModifiedThroughTheResponse() {
        PageResponse<String> response = PageResponse.from(new PageImpl<>(List.of("a"), PageRequest.of(0, 10), 1));

        assertThatThrownBy(() -> response.content().add("b")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void contentIsCopiedSoLaterChangesToTheSourceDoNotLeakIn() {
        List<String> source = new ArrayList<>(List.of("a"));

        PageResponse<String> response = new PageResponse<>(source, 0, 10, 1, 1, true);
        source.add("b");

        assertThat(response.content()).containsExactly("a");
    }

    @Test
    void jsonHasExactlyTheDocumentedProperties() throws Exception {
        PageResponse<String> response = PageResponse.from(new PageImpl<>(List.of("a"), PageRequest.of(0, 10), 1));

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));

        Set<String> names = new HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("content", "page", "size", "totalElements", "totalPages", "last");
    }
}
