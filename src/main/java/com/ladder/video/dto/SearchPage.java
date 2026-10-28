package com.ladder.video.dto;

import java.util.List;

import com.ladder.video.dto.VideoPage.Item;

/**
 * One page of search results. Uses an integer {@code nextOffset} instead of a
 * UUID cursor because results are ordered by relevance score first and a
 * lower-ranked result can have a newer UUIDv7 than a higher-ranked one.
 */
public record SearchPage(List<Item> items, Integer nextOffset) {
}

