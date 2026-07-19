package io.github.fturleque.nexus.search.lucene;

import io.github.fturleque.nexus.config.NexusPaths;
import io.github.fturleque.nexus.index.CodeSymbol;
import io.github.fturleque.nexus.search.SearchDocument;
import io.github.fturleque.nexus.search.SearchIndex;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class LuceneSearchIndex implements SearchIndex {

    private static final String FIELD_DOCUMENT_KEY = "document_key";

    private final NexusPaths paths;

    public LuceneSearchIndex(NexusPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    @Override
    public void applyChanges(UUID projectId, List<SearchDocument> documents, Set<String> removedPaths)
            throws IOException {
        try (IndexResources resources = open(projectId)) {
            for (String relativePath : removedPaths) {
                resources.writer().deleteDocuments(new Term(FIELD_DOCUMENT_KEY, documentKey(projectId, relativePath)));
            }
            for (SearchDocument document : documents) {
                resources.writer().updateDocument(
                        new Term(FIELD_DOCUMENT_KEY, documentKey(projectId, document.relativePath())),
                        toLuceneDocument(projectId, document));
            }
            resources.writer().commit();
        }
    }

    @Override
    public void rebuild(UUID projectId, List<SearchDocument> documents) throws IOException {
        try (IndexResources resources = open(projectId)) {
            resources.writer().deleteAll();
            for (SearchDocument document : documents) {
                resources.writer().addDocument(toLuceneDocument(projectId, document));
            }
            resources.writer().commit();
        }
    }

    private IndexResources open(UUID projectId) throws IOException {
        Path indexPath = paths.projectLuceneIndex(projectId);
        Files.createDirectories(indexPath);
        Directory directory = FSDirectory.open(indexPath);
        Analyzer analyzer = new StandardAnalyzer();
        IndexWriterConfig configuration = new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        try {
            IndexWriter writer = new IndexWriter(directory, configuration);
            return new IndexResources(directory, analyzer, writer);
        } catch (IOException | RuntimeException exception) {
            analyzer.close();
            directory.close();
            throw exception;
        }
    }

    private static Document toLuceneDocument(UUID projectId, SearchDocument source) {
        Document document = new Document();
        document.add(new StringField(
                FIELD_DOCUMENT_KEY,
                documentKey(projectId, source.relativePath()),
                Field.Store.NO));
        document.add(new StringField("project_id", projectId.toString(), Field.Store.NO));
        document.add(new StringField("path", source.relativePath(), Field.Store.YES));
        document.add(new TextField("path_text", source.relativePath(), Field.Store.NO));
        document.add(new StringField("language", source.language(), Field.Store.YES));
        document.add(new StringField("category", source.category().name(), Field.Store.YES));
        document.add(new TextField("content", source.content(), Field.Store.NO));

        for (CodeSymbol symbol : source.symbols()) {
            document.add(new StringField("symbol_exact", symbol.name(), Field.Store.NO));
            document.add(new TextField("symbol_name", symbol.name(), Field.Store.NO));
            document.add(new StringField("qualified_name_exact", symbol.qualifiedName(), Field.Store.NO));
            document.add(new TextField("qualified_name", symbol.qualifiedName(), Field.Store.NO));
            document.add(new StringField("symbol_kind", symbol.kind().name(), Field.Store.NO));
        }
        return document;
    }

    private static String documentKey(UUID projectId, String relativePath) {
        return projectId + ":" + relativePath;
    }

    private record IndexResources(Directory directory, Analyzer analyzer, IndexWriter writer)
            implements AutoCloseable {

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                writer.close();
            } catch (IOException exception) {
                failure = exception;
            }
            analyzer.close();
            try {
                directory.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
