package com.mcan.nexusforge.workflow;

import java.nio.file.Path;

public record WorkflowResult(boolean success, Path runDirectory, Path report, Path diff, String message) {}
