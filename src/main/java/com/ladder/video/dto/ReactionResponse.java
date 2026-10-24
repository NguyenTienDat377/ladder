package com.ladder.video.dto;

// myReaction is null when the caller has no reaction (after a DELETE).
public record ReactionResponse(String myReaction, long likeCount, long dislikeCount) {
}
