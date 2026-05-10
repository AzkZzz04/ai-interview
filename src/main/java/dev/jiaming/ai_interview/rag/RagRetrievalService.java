package dev.jiaming.ai_interview.rag;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class RagRetrievalService {

	private final ObjectProvider<VectorStore> vectorStoreProvider;

	private final RagProperties ragProperties;

	public RagRetrievalService(ObjectProvider<VectorStore> vectorStoreProvider, RagProperties ragProperties) {
		this.vectorStoreProvider = vectorStoreProvider;
		this.ragProperties = ragProperties;
	}

	public List<RagContextSnippet> retrieve(String query) {
		return retrieve(query, ragProperties.defaultTopK());
	}

	public List<RagContextSnippet> retrieve(String query, int topK) {
		String retrievalQuery = normalizedQuery(query);
		if (retrievalQuery.isEmpty()) {
			return List.of();
		}

		SearchRequest request = SearchRequest.builder()
			.query(retrievalQuery)
			.topK(topK)
			.build();

		return vectorStore().similaritySearch(request).stream()
			.map(this::toSnippet)
			.toList();
	}

	public List<RagContextSnippet> retrieve(String query, String corpusId, List<String> sourceTypes, int topK) {
		String retrievalQuery = normalizedQuery(query);
		if (retrievalQuery.isEmpty()) {
			return List.of();
		}

		SearchRequest request = SearchRequest.builder()
			.query(retrievalQuery)
			.topK(topK)
			.filterExpression(filter(corpusId, sourceTypes))
			.build();

		return vectorStore().similaritySearch(request).stream()
			.map(this::toSnippet)
			.toList();
	}

	public void deleteCorpus(String corpusId) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		vectorStore().delete(builder.eq("corpusId", corpusId).build());
	}

	private RagContextSnippet toSnippet(Document document) {
		return new RagContextSnippet(document.getId(), document.getText(), document.getMetadata(), document.getScore());
	}

	private Expression filter(String corpusId, List<String> sourceTypes) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		FilterExpressionBuilder.Op corpusFilter = builder.eq("corpusId", corpusId);
		List<Object> allowedSourceTypes = sourceTypes == null
			? List.of()
			: sourceTypes.stream()
				.filter(Objects::nonNull)
				.map(Object.class::cast)
				.toList();

		if (allowedSourceTypes.isEmpty()) {
			return corpusFilter.build();
		}

		FilterExpressionBuilder.Op sourceFilter = allowedSourceTypes.size() == 1
			? builder.eq("sourceType", allowedSourceTypes.getFirst())
			: builder.in("sourceType", allowedSourceTypes);

		return builder.and(corpusFilter, sourceFilter).build();
	}

	private String normalizedQuery(String query) {
		return query == null ? "" : query.trim();
	}

	private VectorStore vectorStore() {
		VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
		if (vectorStore == null) {
			throw new IllegalStateException("VectorStore is not available");
		}
		return vectorStore;
	}
}
