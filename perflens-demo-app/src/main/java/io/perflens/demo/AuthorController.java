package io.perflens.demo;

import io.perflens.core.annotation.PerfLensTrace;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Two endpoints returning the same JSON, one with an N+1 and one without. */
@RestController
@Transactional(readOnly = true)
public class AuthorController {

    private final AuthorRepository authors;

    public AuthorController(AuthorRepository authors) {
        this.authors = authors;
    }

    /** One query for the authors, then one more per author for its books. */
    @GetMapping("/authors")
    @PerfLensTrace(value = "authors", label = "Authors (lazy books)")
    public List<Map<String, Object>> authors() {
        return toJson(authors.findAll());
    }

    /** Same response from a single query. */
    @GetMapping("/authors/fixed")
    @PerfLensTrace(value = "authors-fixed", label = "Authors (entity graph)")
    public List<Map<String, Object>> authorsFixed() {
        return toJson(authors.findAllWithBooks());
    }

    private static List<Map<String, Object>> toJson(List<Author> list) {
        return list.stream()
                .map(author -> Map.<String, Object>of("name", author.getName(),
                        "books", author.getBooks().stream().map(Book::getTitle).toList()))
                .toList();
    }
}
