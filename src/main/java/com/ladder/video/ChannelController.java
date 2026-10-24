package com.ladder.video;

import java.security.Principal;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ladder.security.CurrentUser;
import com.ladder.video.dto.ChannelPageResponse;

@RestController
@RequestMapping("/api/v1/channels")
public class ChannelController {
    private final CatalogService catalog;

    public ChannelController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/{handle}")
    public ChannelPageResponse channel(@PathVariable String handle,
                                       @RequestParam(required = false) UUID before,
                                       @RequestParam(defaultValue = "" + CatalogService.DEFAULT_LIMIT) int limit,
                                       Principal principal) {
        return catalog.channel(handle, before, limit, CurrentUser.idOrNull(principal));
    }
}
