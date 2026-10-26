package com.maelespecieros.backend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AIInsightsResponse {
    private String insight;
    private boolean systemAltered;
}
