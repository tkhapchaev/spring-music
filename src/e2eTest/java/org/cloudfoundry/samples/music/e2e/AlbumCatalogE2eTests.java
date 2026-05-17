package org.cloudfoundry.samples.music.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.Video;
import com.microsoft.playwright.options.AriaRole;
import org.cloudfoundry.samples.music.config.SpringApplicationContextInitializer;
import org.cloudfoundry.samples.music.domain.Album;
import org.cloudfoundry.samples.music.repositories.AlbumRepositoryPopulator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.repository.CrudRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = SpringApplicationContextInitializer.class)
public class AlbumCatalogE2eTests {
    private static final String E2E_PREFIX = "E2E Spring Music";
    private static final double DEFAULT_SLOW_MO_MS = 500;

    private static Playwright playwright;
    private static Browser browser;

    @LocalServerPort
    private int port;

    @Autowired
    private CrudRepository<Album, String> albumRepository;

    private BrowserContext context;
    private Page page;
    private Path artifactDir;

    @BeforeAll
    public static void launchBrowser() {
        playwright = Playwright.create();

        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(!"false".equalsIgnoreCase(System.getenv("E2E_HEADLESS")))
                .setSlowMo(slowMoMillis());

        String browserChannel = System.getenv("E2E_BROWSER_CHANNEL");

        if (browserChannel != null && !browserChannel.isBlank()) {
            options.setChannel(browserChannel);
        }

        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    public static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }

        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    public void createContext(TestInfo testInfo) throws IOException {
        String testMethodName = testInfo.getTestMethod()
                .map(Method::getName)
                .orElse(testInfo.getDisplayName());

        artifactDir = artifactsRoot().resolve(safeName(testMethodName));

        Files.createDirectories(artifactDir);

        context = browser.newContext(new Browser.NewContextOptions()
                .setRecordVideoDir(artifactDir)
                .setViewportSize(1280, 720));

        context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));

        page = context.newPage();
    }

    @AfterEach
    public void saveArtifactsAndCleanData() throws IOException {
        if (page != null && !page.isClosed()) {
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(artifactDir.resolve("screenshot.png"))
                    .setFullPage(true));
        }

        if (context != null) {
            context.tracing().stop(new Tracing.StopOptions()
                    .setPath(artifactDir.resolve("trace.zip")));

            context.close();
        }

        if (page != null && page.video() != null) {
            Video video = page.video();
            Files.move(video.path(), artifactDir.resolve("video.webm"), REPLACE_EXISTING);
        }

        albumRepository.findAll().forEach(album -> {
            if (album.getTitle() != null && album.getTitle().startsWith(E2E_PREFIX)) {
                albumRepository.deleteById(album.getId());
            }
        });
    }

    @Test
    public void editsAlbumInlineAndCanCancelEditing() {
        openCatalog();

        AlbumData album = uniqueAlbum("Inline Album");

        String canceledTitle = E2E_PREFIX + " Inline Canceled " + UUID.randomUUID();
        String savedTitle = E2E_PREFIX + " Inline Saved " + UUID.randomUUID();

        addAlbum(album);
        clearStatusIfVisible();

        Locator card = albumCard(album.title);
        albumTitle(card, album.title).click();

        Locator titleInput = card.locator("input[name='title']");
        assertThat(titleInput).isVisible();

        titleInput.fill(canceledTitle);
        titleInput.press("Escape");

        assertThat(albumCard(album.title)).isVisible();
        assertThat(albumCard(canceledTitle)).hasCount(0);

        card = albumCard(album.title);
        albumTitle(card, album.title).click();

        titleInput = card.locator("input[name='title']");
        assertThat(titleInput).isVisible();

        titleInput.fill(savedTitle);
        titleInput.press("Enter");

        assertThat(page.locator("#alert")).containsText("Album saved");
        assertThat(albumCard(savedTitle)).isVisible();
        assertThat(albumCard(savedTitle)).containsText(album.artist);
        assertThat(albumCard(album.title)).hasCount(0);
    }

    @Test
    public void validatesAndAddsNewAlbumThroughModalForm() {
        openCatalog();

        AlbumData album = uniqueAlbum("New Album");

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(Pattern.compile("add an album"))).click();

        Locator modal = page.locator(".modal-content");
        assertThat(modal.getByRole(AriaRole.HEADING, new Locator.GetByRoleOptions().setName("Add an album"))).isVisible();

        modal.getByLabel("Album Title").fill(album.title);
        modal.getByLabel("Artist").fill(album.artist);
        modal.getByLabel("Release Year").fill("99");
        modal.getByLabel("Genre").fill(album.genre);

        assertThat(modal.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("OK"))).isDisabled();
        modal.getByLabel("Release Year").fill(album.releaseYear);

        assertThat(modal.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("OK"))).isEnabled();
        modal.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("OK")).click();

        assertThat(page.locator("#alert")).containsText("Album saved");
        assertThat(albumCard(album.title)).isVisible();
        assertThat(albumCard(album.title)).containsText(album.artist);
        assertThat(albumCard(album.title)).containsText(album.releaseYear);
        assertThat(albumCard(album.title)).containsText(album.genre);
    }

    @Test
    public void editsExistingAlbumAndThenDeletesItFromCatalog() {
        openCatalog();

        AlbumData album = uniqueAlbum("Editable Album");

        AlbumData updatedAlbum = uniqueAlbum("Updated Album")
                .withReleaseYear("2025")
                .withGenre("Synthwave");

        addAlbum(album);

        Locator card = albumCard(album.title);
        card.locator(".dropdown-toggle").click();
        card.getByText("edit", new Locator.GetByTextOptions().setExact(true)).click();

        Locator modal = page.locator(".modal-content");
        assertThat(modal.getByRole(AriaRole.HEADING, new Locator.GetByRoleOptions().setName("Edit an album"))).isVisible();

        modal.getByLabel("Album Title").fill(updatedAlbum.title);
        modal.getByLabel("Artist").fill(updatedAlbum.artist);
        modal.getByLabel("Release Year").fill(updatedAlbum.releaseYear);
        modal.getByLabel("Genre").fill(updatedAlbum.genre);
        modal.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("OK")).click();

        assertThat(page.locator("#alert")).containsText("Album saved");
        assertThat(albumCard(updatedAlbum.title)).containsText(updatedAlbum.artist);
        assertThat(albumCard(album.title)).hasCount(0);

        card = albumCard(updatedAlbum.title);
        card.locator(".dropdown-toggle").click();
        card.getByText("delete", new Locator.GetByTextOptions().setExact(true)).click();

        assertThat(page.locator("#alert")).containsText("Album deleted");
        assertThat(albumCard(updatedAlbum.title)).hasCount(0);
    }

    private void openCatalog() {
        page.navigate("http://127.0.0.1:" + port + "/");

        assertThat(page).hasTitle("Spring Music");
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Albums"))).isVisible();
        assertThat(page.getByText("Nevermind", new Page.GetByTextOptions().setExact(true))).isVisible();
    }

    private void addAlbum(AlbumData album) {
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(Pattern.compile("add an album"))).click();
        Locator modal = page.locator(".modal-content");

        modal.getByLabel("Album Title").fill(album.title);
        modal.getByLabel("Artist").fill(album.artist);
        modal.getByLabel("Release Year").fill(album.releaseYear);
        modal.getByLabel("Genre").fill(album.genre);
        modal.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("OK")).click();

        assertThat(page.locator("#alert")).containsText("Album saved");
        assertThat(albumCard(album.title)).isVisible();
    }

    private Locator albumCard(String title) {
        return page.locator(".thumbnail").filter(new Locator.FilterOptions().setHasText(title));
    }

    private Locator albumTitle(Locator card, String title) {
        return card.locator("h4").filter(new Locator.FilterOptions().setHasText(title));
    }

    private void clearStatusIfVisible() {
        Locator closeButton = page.locator("#alert button.close");

        if (closeButton.isVisible()) {
            closeButton.click();
        }
    }

    private AlbumData uniqueAlbum(String name) {
        String suffix = UUID.randomUUID().toString();

        return new AlbumData(
                E2E_PREFIX + " " + name + " " + suffix,
                E2E_PREFIX + " Artist " + suffix,
                "2024",
                "Electronic"
        );
    }

    private Path artifactsRoot() {
        return Path.of(System.getProperty("e2e.artifacts.dir", "build/playwright-results"));
    }

    private String safeName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
    }

    private static double slowMoMillis() {
        String value = System.getenv("E2E_SLOW_MO_MS");

        if (value == null || value.isBlank()) {
            return DEFAULT_SLOW_MO_MS;
        }

        return Double.parseDouble(value);
    }

    private static class AlbumData {
        private final String title;
        private final String artist;
        private final String releaseYear;
        private final String genre;

        private AlbumData(String title, String artist, String releaseYear, String genre) {
            this.title = title;
            this.artist = artist;
            this.releaseYear = releaseYear;
            this.genre = genre;
        }

        private AlbumData withReleaseYear(String releaseYear) {
            return new AlbumData(title, artist, releaseYear, genre);
        }

        private AlbumData withGenre(String genre) {
            return new AlbumData(title, artist, releaseYear, genre);
        }
    }

    @TestConfiguration
    public static class E2eTestConfiguration {

        @Bean
        public AlbumRepositoryPopulator albumRepositoryPopulator() {
            return new AlbumRepositoryPopulator();
        }
    }
}
