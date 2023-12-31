package com.formkiq.idc;

import static com.formkiq.idc.elasticsearch.ElasticsearchService.INDEX;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.formkiq.idc.elasticsearch.Document;
import com.formkiq.idc.elasticsearch.ElasticSeachRequest;
import com.formkiq.idc.elasticsearch.ElasticsearchService;
import com.formkiq.idc.elasticsearch.Status;
import com.formkiq.idc.kafka.TesseractProducer;
import com.formkiq.idc.syntax.QueryTokenizer;
import com.formkiq.idc.syntax.QueryTokensAnalyzer;
import com.formkiq.idc.syntax.Token;
import com.formkiq.idc.syntax.TokenType;
import com.formkiq.idc.syntax.Tokenizer;

import co.elastic.clients.elasticsearch._types.Result;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;

@Controller
@Secured(SecurityRule.IS_AUTHENTICATED)
public class IndexController {

	@Inject
	private ElasticsearchService elasticService;

	@Inject
	private TesseractProducer producer;

	private Tokenizer queryTokenizer = new QueryTokenizer();

	@Value("${storage.directory}")
	private String storageDirectory;

	private QueryTokensAnalyzer tokensAnalyzer = new QueryTokensAnalyzer();

	ElasticSeachRequest createElasticSearchRequest(List<Token> tokens) {
		Token lastToken = null;

		Iterator<Token> itr = tokens.iterator();

		String searchText = null;
		Map<String, String> searchTags = new HashMap<>();

		while (itr.hasNext()) {
			Token token = itr.next();

			if (lastToken == null && TokenType.IDENTIFIER.equals(token.getType())) {
				lastToken = token;
			} else if (TokenType.ASSIGNMENT_OPERATOR.equals(token.getType())) {
				token = itr.next();

				String key = lastToken.getValue();
				if (key.startsWith("[") && key.endsWith("]")) {
					key = key.substring(1, key.length() - 1);
				}

				String value = stripQuotes(token.getValue());

				searchTags.put(key, value);

				lastToken = null;
			} else if (lastToken != null && TokenType.KEYWORD.equals(token.getType())) {
				searchText = lastToken.getValue();
				lastToken = null;
			}
		}

		if (lastToken != null) {
			searchText = lastToken.getValue();
		}

		return new ElasticSeachRequest(stripQuotes(searchText), searchTags);
	}

	@Delete("/documents/{documentId}")
	@Produces(MediaType.APPLICATION_JSON)
	public HttpResponse<?> deleteDocument(@PathVariable String documentId) throws IOException {

		Path filePath = Path.of(storageDirectory, documentId);

		if (filePath.toFile().exists()) {
			Files.walk(filePath).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}

		return this.elasticService.deleteDocument(INDEX, documentId) ? HttpResponse.ok() : HttpResponse.notFound();
	}

	@Delete("/documents/{documentId}/tags/{tagKey}/{tagValue}")
	@Produces(MediaType.APPLICATION_JSON)
	public HttpResponse<?> deleteTagKeyValue(@PathVariable String documentId, @PathVariable String tagKey,
			@PathVariable String tagValue) throws IOException {
		return this.elasticService.deleteDocumentTag(INDEX, documentId, tagKey, tagValue) ? HttpResponse.ok()
				: HttpResponse.notFound();
	}

	@Get("/documents/{documentId}/content")
	public HttpResponse<StreamedFile> download(@PathVariable String documentId) throws IOException {

		Document document = this.elasticService.getDocument(INDEX, documentId);
		if (document != null) {
			MediaType mediaType = MediaType.of(document.getContentType());
			File file = new File(document.getFileLocation());
			InputStream inputStream = new FileInputStream(file);
			return HttpResponse.ok(new StreamedFile(inputStream, mediaType));
		}

		return HttpResponse.notFound();
	}

	@Get("/documents/{documentId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Document getDocument(@PathVariable String documentId) throws IOException {
		return this.elasticService.getDocument(INDEX, documentId);
	}

	@Post(value = "/search", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
	public HttpResponse<SearchResponse> search(@Body SearchRequest request) {

		try {
			List<Document> documents = searchElastic(request);
			documents.forEach(doc -> doc.setContent(null));

			SearchResponse response = new SearchResponse();
			response.setDocuments(documents);

			return HttpResponse.ok(response);

		} catch (IOException e) {
			e.printStackTrace();
			return HttpResponse.badRequest();
		}
	}

	private List<Document> searchElastic(List<Token> tokens) throws IOException {

		ElasticSeachRequest searchRequest = createElasticSearchRequest(tokens);

		List<Document> documents = elasticService.search("documents", searchRequest.getText(), searchRequest.getTags());
		return documents;
	}

	private List<Document> searchElastic(SearchRequest request) throws IOException {

		List<Document> documents = Collections.emptyList();
		String text = request.getText() != null ? request.getText().trim() : null;

		if (text != null) {
			List<Token> tokens = queryTokenizer.tokenize(text);

			boolean valid = tokensAnalyzer.isValid(tokens);

			if (valid) {
				documents = searchElastic(tokens);
			} else {
				documents = elasticService.search("documents", text, null);
			}

		} else {

			documents = elasticService.search("documents", "", null);
		}

		return documents;
	}

	private String stripQuotes(String value) {
		if (value != null && (value.startsWith("\"") || value.startsWith("'"))
				&& (value.endsWith("\"") || value.endsWith("'"))) {
			value = value.substring(1, value.length() - 1);
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	@Patch(value = "/documents/{documentId}", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
	public HttpResponse<Void> updateDocument(@PathVariable String documentId, @Body Map<String, Object> attributes) {

		try {
			Document doc = new Document();

			if (attributes.containsKey("title")) {
				doc.setTitle(attributes.get("title").toString());
			}

			if (attributes.containsKey("tags")) {
				Map<String, Collection<String>> tags = (Map<String, Collection<String>>) attributes.get("tags");
				doc.setTags(tags);
			}

			if (elasticService.updateDocument(INDEX, documentId, doc).result() == Result.Updated) {
				return HttpResponse.ok();
			}

			return HttpResponse.badRequest();

		} catch (IOException e) {
			e.printStackTrace();
			return HttpResponse.badRequest();
		}
	}

	@Post("/upload")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Produces(MediaType.APPLICATION_JSON)
	public MutableHttpResponse<Document> upload(CompletedFileUpload file) throws IOException {

		System.out.println("processing file: " + file.getFilename());
		if ((file.getFilename() == null || file.getFilename().equals(""))) {
			return HttpResponse.badRequest();
		}

		MediaType mediaType = file.getContentType().orElse(MediaType.APPLICATION_OCTET_STREAM_TYPE);

		String documentId = UUID.randomUUID().toString();

		Path filePath = Path.of(storageDirectory, documentId, file.getFilename());
		Files.createDirectories(Path.of(storageDirectory, documentId));
		Files.write(filePath, file.getBytes());

		Document document = new Document();
		document.setStatus(Status.NEW.name());
		document.setDocumentId(documentId);
		document.setContentType(mediaType.getName());
		document.setFileLocation(filePath.toString());
		document.setFilename(file.getFilename());

		this.elasticService.addDocument(INDEX, documentId, document);

		producer.sendTesseractRequest(documentId, filePath.toString());

		Document doc = new Document();
		doc.setDocumentId(documentId);
		return HttpResponse.created(doc);
	}
}