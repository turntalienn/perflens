package io.perflens.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.util.List;

/** An author with a lazily loaded list of books. */
@Entity
public class Author {

    @Id
    private Long id;

    private String name;

    @OneToMany(mappedBy = "author")
    private List<Book> books;

    protected Author() {
    }

    public String getName() {
        return name;
    }

    public List<Book> getBooks() {
        return books;
    }
}
