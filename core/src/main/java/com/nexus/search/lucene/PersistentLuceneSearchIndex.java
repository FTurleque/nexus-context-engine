package com.nexus.search.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.search.LexicalSearchHit;
import com.nexus.search.SearchDocument;
import com.nexus.search.SearchIndex;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Index lexical de production conservant uniquement les readers/searchers chauds.
 *
 * <p>Les writes restent délégués à {@link LuceneSearchIndex}, donc operation-scoped.
 * Le cache est borné ; lorsqu'il est plein, la recherche retombe sur le chemin
 * historique afin de préserver une borne stricte sur les handles persistants.</p>
 */
public final class PersistentLuceneSearchIndex implements SearchIndex {

    static final int MAX_CACHED_PROJECTS = 100;

    private static final String FIELD_PATH = "path";
    private static final String FIELD_LANGUAGE = "language";
    private static final String FIELD_CATEGORY = "category";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_SYMBOL_NAME = "symbol_name";
    private static final String FIELD_QUALIFIED_NAME = "qualified_name";
    private static final String FIELD_PATH_TEXT = "path_text";
    private static final String FIELD_CODE_TERMS = "code_terms";

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
    private final LuceneSearchIndex operationScoped;
    private final BoundedLuceneSearcherCache searcherCache;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PersistentLuceneSearchIndex(NexusPaths paths) {
        this(paths, MAX_CACHED_PROJECTS);
    }

    PersistentLuceneSearchIndex(NexusPaths paths, int cacheCapacity) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.operationScoped = new LuceneSearchIndex(paths);
        this.searcherCache = new BoundedLuceneSearcherCache(cacheCapacity);
    }

    @Override
    public void applyChanges(UUID projectId, List<SearchDocument> documents, Set<String> removedPaths)
            throws IOException {
        ensureOpen();
        operationScoped.applyChanges(projectId, documents, removedPaths);
        searcherCache.refreshIfCached(projectId);
    }

    @Override
    public void rebuild(UUID projectId, List<SearchDocument> documents) throws IOException {
        ensureOpen();
        // Repartir sans reader chaud rend aussi le rebuild sûr si le répertoire
        // doit un jour être remplacé plutôt que modifié en place.
        searcherCache.invalidate(projectId);
        operationScoped.rebuild(projectId, documents);
    }

    @Override
    public List<LexicalSearchHit> search(UUID projectId, String query, int limit) throws IOException {
        ensureOpen();
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }

        Path indexPath = paths.projectLuceneIndex(projectId);
        if (!Files.exists(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        paths.ensurePrivateDirectory(indexPath);

        BoundedLuceneSearcherCache.SearchLookup<List<LexicalSearchHit>> lookup = searcherCache.search(
                projectId,
                indexPath,
                searcher -> search(searcher, query, limit));
        return lookup.cached() ? lookup.value() : operationScoped.search(projectId, query, limit);
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            searcherCache.close();
        }
    }

    int cachedProjectCount() {
        return searcherCache.cachedProjectCount();
    }

    private static List<LexicalSearchHit> search(IndexSearcher searcher, String query, int limit)
            throws IOException {
        try (Analyzer analyzer = new StandardAnalyzer()) {
            Query luceneQuery = parseQuery(query, analyzer);
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

    private static Query parseQuery(String query, Analyzer analyzer) throws IOException {
        MultiFieldQueryParser parser = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer, FIELD_BOOSTS);
        parser.setDefaultOperator(QueryParser.Operator.OR);
        try {
            List<String> analyzedTerms = analyzeUniqueTerms(query, analyzer);
            if (analyzedTerms.size() < 2) {
                return parser.parse(QueryParser.escape(query.trim()));
            }

            BooleanQuery.Builder coordinated = new BooleanQuery.Builder();
            for (String term : analyzedTerms) {
                coordinated.add(parser.parse(QueryParser.escape(term)), BooleanClause.Occur.SHOULD);
            }
            coordinated.setMinimumNumberShouldMatch(2);
            return coordinated.build();
        } catch (ParseException exception) {
            throw new IOException("Requête Lucene invalide : " + query, exception);
        }
    }

    private static List<String> analyzeUniqueTerms(String query, Analyzer analyzer) throws IOException {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        try (TokenStream tokenStream = analyzer.tokenStream(FIELD_CONTENT, query)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                terms.add(termAttribute.toString());
                if (terms.size() >= LuceneSearchIndex.MAX_ANALYZED_QUERY_TERMS) {
                    break;
                }
            }
            tokenStream.end();
        }
        return List.copyOf(terms);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Persistent lexical Lucene index is closed");
        }
    }
}
