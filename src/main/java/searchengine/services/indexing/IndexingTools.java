package searchengine.services.indexing;

import com.sun.istack.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import searchengine.config.SitesList;
import searchengine.model.Status;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.lemma.LemmaService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.*;

@Slf4j
@Setter
@Getter
@Component
@RequiredArgsConstructor
public class IndexingTools {
    private ExecutorService executorService;
    private static final int CORE_COUNT = Runtime.getRuntime().availableProcessors();
    private Boolean isActive = true;
    private BlockingQueue<PageEntity> blockingQueue = new LinkedBlockingQueue<>(1_00);
    private boolean indexingStarted;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final SiteRepository siteRepository;
    private final LemmaService lemmaService;

    public synchronized void  startTreadsIndexing(SiteEntity siteEntity) {

        log.warn("Full indexing will be started now");
        ForkJoinPool joinPool = new ForkJoinPool();
        if (!isActive) {
            stopActions(joinPool);
        }
        setIndexingStarted(true);
        CountDownLatch latch = new CountDownLatch(2);
        logInfo(siteEntity);
        Thread recursivThread = new Thread(() -> RecursiveThreadBody(joinPool, siteEntity, latch));
        Thread lemmasCollectorThread = new Thread(() -> lemmasThreadBody(siteEntity, latch));
        recursivThread.start();
        lemmasCollectorThread.start();
        try {
            latch.await();

        } catch (InterruptedException e) {
            System.out.println(e.getMessage());
        }
        updateEntity(siteEntity);

        this.setIndexingStarted(false);
    }


    private void lemmasThreadBody(SiteEntity siteEntity, CountDownLatch latch) {
        lemmaService.setQueue(blockingQueue);
        lemmaService.setDone(false);
        lemmaService.setSiteEntity(siteEntity);
        lemmaService.startCollecting();
        latch.countDown();
        log.warn("thread finished, latch =  " + latch.getCount());
    }

    private void RecursiveThreadBody(@NotNull ForkJoinPool pool, SiteEntity siteEntity, @NotNull CountDownLatch latch) {

        RecursiveMake action = new RecursiveMake(siteEntity.getUrl(), siteEntity, blockingQueue, pageRepository, siteEntity.getUrl());
        pool.invoke(action);
        latch.countDown();
        lemmaService.setDone(true);
        log.info(pageRepository.countBySiteEntity(siteEntity) + " pages saved in DB");
        log.warn("thread finished, latch =  " + latch.getCount());
    }


    private void stopActions(ForkJoinPool pool) {
        try {
            log.warn("---остановлено пользователем----");
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            System.out.println(e.getMessage());
        } finally {
            pool.shutdownNow();
            this.setIsActive(true);
            this.setIndexingStarted(false);

        }
    }

    public void setIsActive(boolean value) {
        isActive = value;
        lemmaService.setOffOn(value);
        RecursiveMake.isActive = value;
    }
    private void updateEntity(SiteEntity siteEntity) {
        siteEntity.setStatus(Status.INDEXED);
        siteEntity.setLastError("");
        siteEntity.setStatusTime(LocalDateTime.now());
        int countPages = pageRepository.countBySiteEntity(siteEntity);
        switch (countPages) {
            case 0 -> {
                siteEntity.setStatus(Status.FAILED);
                siteEntity.setLastError("Ошибка индексации: главная страница сайта не доступна");
            }
            case 1 -> {
                siteEntity.setStatus(Status.FAILED);
                siteEntity.setLastError("Ошибка индексации: сайт не доступен");
            }
        }
        if (isActive) {
            log.warn("Status of site " + siteEntity.getName()
                    + " set to " + siteEntity.getStatus().toString()
                    + ", error set to " + siteEntity.getLastError());
        } else {
            siteEntity.setLastError("Индексация остановлена пользователем");
            siteEntity.setStatus(Status.FAILED);
            log.warn("Status of site " + siteEntity.getName()
                    + " set to " + siteEntity.getStatus().toString()
                    + ", error set to " + siteEntity.getLastError());
        }

        siteRepository.save(siteEntity);
        StringPool.clearAll();
    }



    private void logInfo(SiteEntity siteEntity) {
        log.info(siteEntity.getName() + " with URL " + siteEntity.getUrl() + " started indexing");
        log.info(pageRepository.count() + " pages, "
                + lemmaRepository.count() + " lemmas, "
                + indexRepository.count() + " indexes in table");
    }






}