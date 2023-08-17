package searchengine.tools.indexing;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.UncheckedIOException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.springframework.core.env.Environment;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repositories.PageRepository;
import searchengine.tools.AcceptableContentTypes;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.lang.Thread.sleep;
import static searchengine.tools.Regex.*;
import static searchengine.tools.StringPool.*;

@Slf4j
@Getter
@Setter
@RequiredArgsConstructor
public class ScrapingAction extends RecursiveAction {

	public static volatile Boolean enabled = true;
	public String homeUrl;
	public String siteUrl;
	private String currentUrl;
	private Document document;
	private String parentPath;
	private SiteEntity siteEntity;
	private PageEntity pageEntity;
	private Set<String> childLinksOfTask;
	private Connection.Response response = null;
	private BlockingQueue<PageEntity> outcomeQueue;
	private Environment environment;
	private final PageRepository pageRepository;
	private final ReadWriteLock lock = new ReentrantReadWriteLock();
	private static final AcceptableContentTypes ACCEPTABLE_CONTENT_TYPES = new AcceptableContentTypes();

	public ScrapingAction(String currentUrl,
	                       SiteEntity siteEntity,
	                      BlockingQueue<PageEntity> outcomeQueue, Environment environment, PageRepository pageRepository, String homeUrl, String siteUrl) {
		this.siteEntity = siteEntity;
		this.outcomeQueue = outcomeQueue;
		this.currentUrl = currentUrl;
		this.environment = environment;
		this.pageRepository = pageRepository;
		this.homeUrl = homeUrl;
		this.siteUrl = siteUrl;
	}

	@Override
	protected void compute() {
		if (!enabled)
			return;

		response = getResponseFromUrl(currentUrl);
		if (response != null) {
			saveExtractedPage();

			final Elements elements = document.select("a[href]");
			if (!elements.isEmpty()) {
				childLinksOfTask = getChildLinks(currentUrl, elements);
			}

			if (childLinksOfTask != null) forkAndJoinTasks();
		}
	}

	public Set<String> getChildLinks(String url, Elements elements) {
		Set<String> newChildLinks = new HashSet<>();

		for (Element element : elements) {
			final String href = getHrefFromElement(element).toLowerCase(Locale.ROOT);

			lock.readLock().lock();
			if (visitedLinks.containsKey(href))
				continue;
			else if (urlIsValidToProcess(url, newChildLinks, href)) {
				addHrefToOutcomeValue(newChildLinks, href);
			}
			lock.readLock().unlock();
		}
		return newChildLinks;
	}

	private void addHrefToOutcomeValue(Set<String> newChildLinks, String href) {
		if (!visitedLinks.containsKey(href)
				&& !pages404.containsKey(href)
				&& !savedPaths.containsKey(href)) {
			newChildLinks.add(href);
		}
	}

	private boolean urlIsValidToProcess(String sourceUrl, Set<String> newChildLinks, String extractedHref) {
		return sourceUrl.matches(URL_IS_VALID)
				&& extractedHref.startsWith(siteUrl)
				&& !extractedHref.contains("#")
				&& !extractedHref.equals(sourceUrl)
				&& !newChildLinks.contains(extractedHref)
				&&
				(HTML_EXT.stream().anyMatch(extractedHref.substring(extractedHref.length() - 4)::contains)
						| !extractedHref.matches(URL_IS_FILE_LINK));
	}


	private Connection.@Nullable Response getResponseFromUrl(String url) {

		lock.readLock().lock();
		if (!visitedLinks.containsKey(url)) internVisitedLinks(url);
		else
			return null;
		lock.readLock().unlock();

		try {
			response = Jsoup.connect(url).userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) Gecko/20070725 Firefox/2.0.0.6")
					.referrer("http://www.google.com").execute();
			if (ACCEPTABLE_CONTENT_TYPES.contains(response.contentType())) {
				document = response.parse();
				parentPath = "/" + url.replace(homeUrl, "");
				cleanHtmlContent();
				pageEntity = new PageEntity(siteEntity, response.statusCode(), document.html(), parentPath);
			} else
				return null;
		} catch (IOException | UncheckedIOException exception) {
			urlNotAvailableActions(url, exception);
			return null;
		}
		if (Objects.equals(environment.getProperty("user-settings.logging-enable"), "true")) {
			log.info("Response from " + url + " got successfully");
		}

		return response;
	}

	private void urlNotAvailableActions(String url, @NotNull Exception exception) {
		siteEntity.setLastError(exception.getMessage());
		siteEntity.setStatusTime(LocalDateTime.now());
		internPage404(url);
		if (Objects.equals(environment.getProperty("user-settings.logging-enable"), "true")) {
			log.error("Something went wrong 404. " + url + " Pages404 vault contains " + pages404.size() + " url");
		}
	}

	private void cleanHtmlContent() {
		final String oldTitle = document.title();
		final Safelist safelist = Safelist.relaxed().preserveRelativeLinks(true);
		final Cleaner cleaner = new Cleaner(safelist);
		boolean isValid = cleaner.isValid(document);
		if (!isValid) {
			document = cleaner.clean(document);
			document.title(oldTitle);
		}
	}

	private void saveExtractedPage() {
			lock.readLock().lock();
			if (!savedPaths.containsKey(parentPath)){
				pageRepository.save(pageEntity);
				internSavedPath(pageEntity.getPath());
				putPageEntityToOutcomeQueue();
				writeLogAboutEachPage();
			}
			lock.readLock().unlock();
	}

	private void forkAndJoinTasks() {
		if (!enabled)
			return;

		List<ScrapingAction> subTasks = new LinkedList<>();

		for (String childLink : childLinksOfTask) {
			if (childIsValidToFork(childLink)
					&& !pages404.containsKey(childLink)
					&& !visitedLinks.containsKey(childLink)) {
				ScrapingAction action = new ScrapingAction(childLink, siteEntity, outcomeQueue, environment, pageRepository, homeUrl, siteUrl);
				action.fork();
				subTasks.add(action);
			}
		}
		for (ScrapingAction task : subTasks) task.join();
	}

	private boolean childIsValidToFork(@NotNull String subLink) {
		final String ext = subLink.substring(subLink.length() - 4);
		return (HTML_EXT.stream().anyMatch(ext::contains) | !subLink.matches(URL_IS_FILE_LINK));
	}

	public String getHrefFromElement(Element element) {
		return (element != null) ? element.absUrl("href") : "";
	}

	private void writeLogAboutEachPage() {
		if (Objects.equals(environment.getProperty("user-settings.logging-enable"), "true")) {
			log.warn(pageEntity.getPath() + " saved");
		}
	}

	private void putPageEntityToOutcomeQueue() {
		try {
			while (true) {
				if (outcomeQueue.remainingCapacity() < 5 && enabled)
					sleep(5_000);
				else
					break;
			}
			outcomeQueue.put(pageEntity);
		} catch (InterruptedException ex) {
			log.error("Can't put pageEntity to outcomeQueue");
		}
	}
}
