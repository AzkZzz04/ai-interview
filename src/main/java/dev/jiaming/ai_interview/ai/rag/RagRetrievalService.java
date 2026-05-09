package dev.jiaming.ai_interview.ai.rag;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(VectorStore.class)
public class RagRetrievalService {

	private final VectorStore vectorStore;

	private final RagProperties ragProperties;

	public RagRetrievalService(VectorStore vectorStore, RagProperties ragProperties) {
		this.vectorStore = vectorStore;
		this.ragProperties = ragProperties;
	}

	public List<RagContextSnippet> retrieve(String query) {
		return retrieve(query, ragProperties.defaultTopK());
	}

	public List<RagContextSnippet> retrieve(String query, int topK) {
		SearchRequest request = SearchRequest.builder()
			.query(query)
			.topK(topK)
			.build();

		return vectorStore.similaritySearch(request).stream()
			.map(this::toSnippet)
			.toList();
	}

	private RagContextSnippet toSnippet(Document document) {
		return new RagContextSnippet(document.getText(), document.getMetadata());
	}
}
