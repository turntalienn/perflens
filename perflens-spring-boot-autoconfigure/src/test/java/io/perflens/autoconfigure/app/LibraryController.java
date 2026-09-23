package io.perflens.autoconfigure.app;

import io.perflens.core.annotation.PerfLensTrace;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LibraryController {

    private final LibraryService service;
    private final NightlyJob job;
    private final PlateWorker plateWorker;

    public LibraryController(LibraryService service, NightlyJob job, PlateWorker plateWorker) {
        this.service = service;
        this.job = job;
        this.plateWorker = plateWorker;
    }

    @GetMapping("/plates/parallel")
    @PerfLensTrace("parallel-plates")
    int parallelPlates() throws Exception {
        List<CompletableFuture<Integer>> results = List.of(plateWorker.process(1), plateWorker.process(2),
                plateWorker.process(3));
        return results.stream().mapToInt(CompletableFuture::join).sum();
    }

    @GetMapping("/authors/lazy")
    @PerfLensTrace("authors-lazy")
    int lazy() {
        return service.countBooksLazily();
    }

    @GetMapping("/authors/fetch-join")
    @PerfLensTrace(value = "authors-fetch-join", label = "Authors with fetch join")
    int fetchJoin() {
        return service.countBooksWithFetchJoin();
    }

    @GetMapping("/authors/count-twice")
    @PerfLensTrace("count-twice")
    int countTwice() {
        return service.countAuthorsTwice();
    }

    @GetMapping("/slow-tx")
    @PerfLensTrace("slow-tx")
    String slowTransaction() throws InterruptedException {
        service.slowTransaction();
        return "ok";
    }

    @GetMapping("/nested-tx")
    @PerfLensTrace("nested-tx")
    String nestedTransaction() {
        service.nestedTransaction();
        return "ok";
    }

    @GetMapping("/db-enabled")
    @PerfLensTrace("db-enabled")
    List<String> dbEnabled() {
        return service.names();
    }

    @GetMapping("/not-enabled")
    @PerfLensTrace("not-enabled")
    List<String> notEnabled() {
        return service.names();
    }

    @GetMapping("/plain")
    List<String> plain() {
        return service.names();
    }

    @GetMapping("/job")
    String runJob() {
        return job.run();
    }
}
