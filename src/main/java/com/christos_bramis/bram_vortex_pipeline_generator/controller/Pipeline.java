package com.christos_bramis.bram_vortex_pipeline_generator.controller;

import com.christos_bramis.bram_vortex_pipeline_generator.repository.PipelineJobRepository;
import com.christos_bramis.bram_vortex_pipeline_generator.service.PipelineService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/pipeline")
public class Pipeline {

    private final PipelineService pipelineService;
    private final PipelineJobRepository pipelineJobRepository;

    public Pipeline(PipelineService pipelineService, PipelineJobRepository pipelineJobRepository) {
        this.pipelineService = pipelineService;
        this.pipelineJobRepository = pipelineJobRepository;
    }

    /**
     * Endpoint που δέχεται το Webhook από τον Repo Analyzer.
     * Πλέον το userId έρχεται από το επικυρωμένο JWT Token.
     */
    @PostMapping("/generate/{analysisJobId}")
    public ResponseEntity<String> generatePipeline(
            @PathVariable String analysisJobId,
            Authentication auth) { // <--- Λήψη του χρήστη από το Security Context

        String userId = auth.getName();
        System.out.println("🚀 [PIPELINE CONTROLLER] Webhook received for Job: " + analysisJobId + " from User: " + userId);

        try {
            String pipelineJobId = UUID.randomUUID().toString();

            // Ξεκινάμε την παραγωγή (Async) χρησιμοποιώντας το userId από το Token
            pipelineService.generateAndSavePipeline(pipelineJobId, analysisJobId, userId);

            return ResponseEntity.ok(pipelineJobId);
        } catch (Exception e) {
            System.err.println("❌ [CONTROLLER ERROR]: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Error starting generation: " + e.getMessage());
        }
    }

    @GetMapping("/download/by-analysis/{analysisJobId}")
    public ResponseEntity<byte[]> downloadPipelineByAnalysisId(
            @PathVariable String analysisJobId,
            Authentication auth) {

        String userId = auth.getName();
        System.out.println("📦 [PIPELINE] Download request for Analysis Job: " + analysisJobId + " by User: " + userId);

        return pipelineJobRepository.findByAnalysisJobId(analysisJobId)
                .map(job -> {
                    if (!job.getUserId().equals(userId)) {
                        System.err.println("🚫 [SECURITY] Unauthorized access attempt by user: " + userId);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<byte[]>build();
                    }

                    // ΕΔΩ Η ΔΙΟΡΘΩΣΗ: Προσθήκη ελέγχου length == 0
                    if (!"COMPLETED".equals(job.getStatus()) || job.getPipelineZip() == null || job.getPipelineZip().length == 0) {
                        return ResponseEntity.status(HttpStatus.ACCEPTED).<byte[]>build(); // Επιστρέφει 202
                    }

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", "vortex-pipeline-" + analysisJobId + ".zip");
                    headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

                    return new ResponseEntity<>(job.getPipelineZip(), headers, HttpStatus.OK);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Status endpoint βάσει Analysis ID (για το Frontend)
     */
    @GetMapping("/status/by-analysis/{analysisJobId}")
    public ResponseEntity<String> getStatusByAnalysis(@PathVariable String analysisJobId, Authentication auth) {
        String userId = auth.getName();
        return pipelineJobRepository.findByAnalysisJobId(analysisJobId)
                .map(job -> {
                    if (!job.getUserId().equals(userId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<String>build();
                    }
                    return ResponseEntity.ok(job.getStatus());
                })
                .orElse(ResponseEntity.ok("GENERATING")); // Αν δεν έχει ξεκινήσει ακόμα
    }
}