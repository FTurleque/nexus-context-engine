package com.nexus.search.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.index.CodeSymbol;
import com.nexus.index.FileCategory;
import com.nexus.search.LexicalSearchHit;
import com.nexus.search.SearchDocument;
import com.nexus.search.SearchIndex;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class LuceneSearchIndex implements SearchIndex {

    private static final String FIELD_DOCUMENT_KEY = "document_key";
    private static final String FIELD_PATH = "path";
    private static final String FIELD_PATH_TEXT = "path_text";
    private static final String FIELD_LANGUAGE = "language";
    private static final String FIELD_CATEGORY = "category";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_SYMBOL_NAME = "symbol_name";
    private static final String FIELD_QUALIFIED_NAME = "qualified_name";
    private static final String FIELD_CODE_TERMS = "code_terms";

    private static final Pattern LOWER_OR_DIGIT_TO_UPPER =
            Pattern.compile("(?<=[\\p{Ll}\\p{Nd}])(?=\\p{Lu})");
    private static final Pattern ACRONYM_TO_WORD =
            Pattern.compile("(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final String[] SEARCH_FIELDS = {
            FIELD_SYMBOL_NAME,
            FIELD_QUALIFIED_NAME,
            FIELD_PATH_TEXT,
            FIELD_CODE_TERMS,
            FIELD_CONTENT
    };

    private static final Map<String, Float> FIELD_BOOSTS = Map.of(
            FIELD_SYMBOL_NAME, 5.0f,
            FIELD_QUALIFIED_NAME, 4.0f,
            FIELD_PATH_TEXT, 3.0f,
            FIELD_CODE_TERMS, 2.0f,
            FIELD_CONTENT, 1.0f);

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

    @Override
    public List<LexicalSearchHit> search(UUID projectId, String query, int limit) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }

        Path indexPath = paths.projectLuceneIndex(projectId);
        if (!Files.isDirectory(indexPath)) {
            return List.of();
        }

        try (Directory directory = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(directory)) {
                return List.of();
            }
            try (DirectoryReader reader = DirectoryReader.open(directory);
                 Analyzer analyzer = new StandardAnalyzer()) {
                Query luceneQuery = parseQuery(query, analyzer);
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs topDocs = searcher.search(luceneQuery, limit);
                List<LexicalSearchHit> hits = new ArrayList<>(topDocs.scoreDocs.length);
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    Document document = searcher.storedFields().document(scoreDoc.doc);
                    hits.add(new LexicalSearchHit(
                            document.get(FIELD_PATH),
                            document.get(FIELD_LANGUAGE),
                            FileCategory.valueOf(document.get(FIELD_CATEGORY)),
                            scoreDoc.score));
                }
                return List.copyOf(hits);
            }
        }
    }

    private static Query parseQuery(String query, Analyzer analyzer) throws IOException {
        MultiFieldQueryParser parser = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer, FIELD_BOOSTS);
        parser.setDefaultOperator(QueryParser.Operator.OR);
        try {
            return parser.parse(QueryParser.escape(query.trim()));
        } catch (ParseException exception) {
            throw new IOException("Requête Lucene invalide : " + query, exception);
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
        document.add(new StringField(FIELD_PATH, source.relativePath(), Field.Store.YES));
        document.add(new TextField(FIELD_PATH_TEXT, source.relativePath(), Field.Store.NO));
        document.add(new StringField(FIELD_LANGUAGE, source.language(), Field.Store.YES));
        document.add(new StringField(FIELD_CATEGORY, source.category().name(), Field.Store.YES));
        document.add(new TextField(FIELD_CONTENT, source.content(), Field.Store.NO));
        document.add(new TextField(
                FIELD_CODE_TERMS,
                identifierSearchText(source.relativePath()) + " " + identifierSearchText(source.content()),
                Field.Store.NO));

        for (CodeSymbol symbol : source.symbols()) {
            document.add(new StringField("symbol_exact", symbol.name(), Field.Store.NO));
            document.add(new TextField(FIELD_SYMBOL_NAME, symbol.name(), Field.Store.NO));
            document.add(new StringField("qualified_name_exact", symbol.qualifiedName(), Field.Store.NO));
            document.add(new TextField(FIELD_QUALIFIED_NAME, symbol.qualifiedName(), Field.Store.NO));
            document.add(new TextField(
                    FIELD_CODE_TERMS,
                    identifierSearchText(symbol.name()) + " " + identifierSearchText(symbol.qualifiedName()),
                    Field.Store.NO));
            document.add(new StringField("symbol_kind", symbol.kind().name(), Field.Store.NO));
        }
        return document;
    }

    private static String identifierSearchText(String value) {
        String normalized = LOWER_OR_DIGIT_TO_UPPER.matcher(value).replaceAll(" ");
        normalized = ACRONYM_TO_WORD.matcher(normalized).replaceAll(" ");
        return NON_ALPHANUMERIC.matcher(normalized).replaceAll(" ").trim();
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
