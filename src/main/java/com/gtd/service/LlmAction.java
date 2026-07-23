package com.gtd.service;

/**
 * The LLM-backed steps a capture flows through, each independently routable to its own provider
 * (Triage always runs; Enrichment or Resolver runs per create op depending on confidence).
 * Room to add TRANSCRIPTION / MARKDOWNIFY later — MarkdownifyService is the natural next one;
 * adding a value here plus its default entry in LlmProviderService is all it takes, nothing else
 * enumerates these.
 */
public enum LlmAction { TRIAGE, ENRICHMENT, RESOLVER }
