package com.christos_bramis.bram_vortex_pipeline_generator.service;

import com.christos_bramis.bram_vortex_pipeline_generator.entity.AnalysisJob;
import com.christos_bramis.bram_vortex_pipeline_generator.entity.PipelineJob;
import com.christos_bramis.bram_vortex_pipeline_generator.repository.AnalysisJobRepository;
import com.christos_bramis.bram_vortex_pipeline_generator.repository.PipelineJobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class PipelineService {

    private final PipelineJobRepository pipelineJobRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public PipelineService(PipelineJobRepository pipelineJobRepository,
                           AnalysisJobRepository analysisJobRepository,
                           ChatModel chatModel) {
        this.pipelineJobRepository = pipelineJobRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    public void generateAndSavePipeline(String pipelineJobId, String analysisJobId, String userId) {
        System.out.println("\n🚀 [VORTEX-PIPELINE] Starting Generation for Job: " + pipelineJobId);

        PipelineJob job = new PipelineJob();
        job.setId(pipelineJobId);
        job.setAnalysisJobId(analysisJobId);
        job.setUserId(userId);
        job.setStatus("GENERATING");
        pipelineJobRepository.save(job);

        CompletableFuture.runAsync(() -> {
            try {
                // 1. Fetching Blueprint
                AnalysisJob analysisJob = analysisJobRepository.findById(analysisJobId)
                        .orElseThrow(() -> new RuntimeException("Analysis blueprint not found"));

                String blueprintJson = analysisJob.getBlueprintJson() != null ?
                        analysisJob.getBlueprintJson() : "{}";

                // 🌟 ΕΞΥΠΝΗ ΕΞΑΓΩΓΗ: Παίρνουμε το computeCategory απευθείας από το JSON
                Map<String, Object> blueprintMap = objectMapper.readValue(blueprintJson, new TypeReference<Map<String, Object>>() {});
                String computeType = (String) blueprintMap.get("computeCategory");

                // Αν δεν υπάρχει, ρίχνουμε Exception για να σταματήσει η διαδικασία (Fail-Fast)
                if (computeType == null || computeType.trim().isEmpty()) {
                    throw new RuntimeException("CRITICAL: 'computeCategory' is missing from the Blueprint. Cannot determine deployment target.");
                }

                System.out.println("🚀 [VORTEX-PIPELINE] Target detected from JSON: " + computeType);

                // 2. AI Dispatch - CI/CD Expert Prompt
                String prompt = String.format("""
                    You are a Principal DevOps Engineer and CI/CD Specialist.
                    Generate a PRODUCTION-READY GitHub Actions workflow (.yml) to build, push, and deploy a containerized application.
                
                    --- ARCHITECTURAL BLUEPRINT (JSON) ---
                    %s
                    --------------------------------------
                    
                    --- DEPLOYMENT TARGET ---
                    Compute Type: %s
                    -------------------------

                    ENGINEERING REQUIREMENTS:
                    1. **Trigger**: The pipeline should trigger on 'push' to the 'main' or 'master' branch.
                    2. **Build & Push**:
                       - Checkout the code.
                       - Log in to the GitHub Container Registry (ghcr.io) using the automatic `secrets.GITHUB_TOKEN`.
                       - Build the Docker image (if applicable based on ciCdMetadata).
                       - Push the image to `ghcr.io/${{ github.repository }}:latest`.
                    3. **Deployment Step**:
                       - Based on the "Compute Type" (%s), add the final deployment step.
                       - If VM or Virtual Machine: SSH into the instance using `secrets.VM_SSH_KEY` and run/update the docker container.
                       - If Kubernetes (K8S): Set up Kubeconfig using `secrets.KUBECONFIG` and run `kubectl apply` or `kubectl set image`.
                       - If Managed Container: Use standard cloud actions to update the target service.

                    OUTPUT FORMAT:
                    - Respond ONLY with a SINGLE, VALID JSON object.
                    - NO markdown blocks (```json).
                    - NO conversational text.
    
                    JSON STRUCTURE:
                    {
                      ".github/workflows/deploy.yml": "YOUR_YAML_CONTENT_HERE"
                    }
                    """, blueprintJson, computeType, computeType);

                System.out.println("🧠 [PIPELINE] Calling AI...");
                String aiResponse = chatModel.call(prompt);

                System.out.println("DEBUG AI RAW RESPONSE length: " + (aiResponse != null ? aiResponse.length() : 0));

                // 3. Robust Parsing
                Map<String, String> asFiles = parseResponse(aiResponse);

                if (asFiles == null || asFiles.isEmpty()) {
                    throw new RuntimeException("AI returned empty file set or invalid JSON format");
                }

                // 4. Zipping
                System.out.println("🤐 [PIPELINE] Creating ZIP for " + asFiles.size() + " files...");
                byte[] zipBytes = createZipInMemory(asFiles);

                if (zipBytes == null || zipBytes.length < 100) {
                    throw new RuntimeException("Generated ZIP is suspiciously small (" + (zipBytes != null ? zipBytes.length : 0) + " bytes)");
                }

                // 5. Finalize
                job.setPipelineZip(zipBytes);
                job.setStatus("COMPLETED");
                pipelineJobRepository.save(job);
                System.out.println("✅ [PIPELINE] Success! ZIP size: " + zipBytes.length + " bytes.");

            } catch (Exception e) {
                System.err.println("❌ [PIPELINE ERROR]: " + e.getMessage());
                job.setStatus("FAILED");
                pipelineJobRepository.save(job);
            }
        });
    }

    private Map<String, String> parseResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            System.err.println("⚠️ [PARSING ERROR] AI response is null or empty");
            return new HashMap<>();
        }

        try {
            String clean = response.trim();
            // Αφαίρεση markdown αν το AI παρακούσει
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```json\\s*", "").replaceAll("```$", "").trim();
            }

            // Χρήση ObjectMapper για μετατροπή του String σε Map
            return objectMapper.readValue(clean, new TypeReference<HashMap<String, String>>() {});
        } catch (Exception e) {
            System.err.println("⚠️ [PARSING ERROR] Failed to convert AI response to Map: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private byte[] createZipInMemory(Map<String, String> files) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) continue;

                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.finish();
            zos.flush();
        }
        return baos.toByteArray();
    }
}