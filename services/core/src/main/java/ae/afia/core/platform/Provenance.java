package ae.afia.core.platform;

/** Which model, prompt and policy produced a derived artifact, as reported by model-gateway. */
public record Provenance(String executionId, String modelId, String modelVersion, String promptId,
                         Integer promptVersion, String policyVersion, String route) {

    public void requireComplete() {
        if (isBlank(executionId) || isBlank(modelId) || isBlank(modelVersion) || isBlank(policyVersion)
                || isBlank(route)) {
            throw ApiException.badRequest("provenance_required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
