package com.ladder.video;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ladder.security.CurrentUser;
import com.ladder.video.dto.ReactionRequest;
import com.ladder.video.dto.ReactionResponse;
import com.ladder.video.dto.SubscribedChannel;
import com.ladder.video.dto.SubscriptionResponse;

import jakarta.validation.Valid;

// None of these paths is permitted anonymously in SecurityConfig (only GETs of
// the read side are), so reaching a method means the caller is logged in.
@RestController
public class EngagementController {
    private final EngagementService engagement;

    public EngagementController(EngagementService engagement) {
        this.engagement = engagement;
    }

    // PUT and DELETE are idempotent: repeating either leaves the same state.
    @PutMapping("/api/v1/channels/{handle}/subscription")
    public SubscriptionResponse subscribe(@PathVariable String handle, Principal principal) {
        return engagement.subscribe(handle, CurrentUser.idOrNull(principal));
    }

    @DeleteMapping("/api/v1/channels/{handle}/subscription")
    public SubscriptionResponse unsubscribe(@PathVariable String handle, Principal principal) {
        return engagement.unsubscribe(handle, CurrentUser.idOrNull(principal));
    }

    @PutMapping("/api/v1/videos/{id}/reaction")
    public ReactionResponse react(@PathVariable UUID id, @Valid @RequestBody ReactionRequest request,
                                  Principal principal) {
        return engagement.react(id, CurrentUser.idOrNull(principal), request.reaction());
    }

    @DeleteMapping("/api/v1/videos/{id}/reaction")
    public ReactionResponse unreact(@PathVariable UUID id, Principal principal) {
        return engagement.unreact(id, CurrentUser.idOrNull(principal));
    }

    @GetMapping("/api/v1/subscriptions")
    public List<SubscribedChannel> subscriptions(Principal principal) {
        return engagement.subscriptions(CurrentUser.idOrNull(principal));
    }
}
