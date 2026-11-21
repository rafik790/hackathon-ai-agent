package com.hackathon.agent.controller;

import com.hackathon.agent.dto.CollectorStatusResponse;
import com.hackathon.agent.service.CollectorStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/collector")
@Tag(name = "Collector", description = "State of the Tekton collection loop")
public class CollectorController {

    private final CollectorStatusService statusService;

    public CollectorController(CollectorStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    @Operation(summary = "Collector status", description = "Outcome of the most recent collection cycle.")
    public CollectorStatusResponse status() {
        return statusService.status();
    }
}
