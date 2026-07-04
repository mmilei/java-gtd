package com.gtd.service;

import com.fasterxml.jackson.annotation.JsonValue;

/** Who performed a mutation — a human via the UI (bucket endpoints) or the LLM via chat. */
public enum Actor {
    USER, LLM;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
