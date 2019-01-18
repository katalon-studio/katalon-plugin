package com.katalon.jenkins.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.FilePath;
import hudson.model.BuildListener;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

class KatalonUtils {

    private static final String RELEASES_LIST =
            "https://raw.githubusercontent.com/katalon-studio/katalon-studio/master/releases.json";

    private static KatalonVersion getVersionInfo(BuildListener buildListener, String versionNumber) throws IOException {

        URL url = new URL(RELEASES_LIST);

        String os = OsUtils.getOSVersion(buildListener);

        LogUtils.log(buildListener, "Retrieve Katalon Studio version: " + versionNumber + ", OS: " + os);

        ObjectMapper objectMapper = new ObjectMapper();
        List<KatalonVersion> versions = objectMapper.readValue(url, new TypeReference<List<KatalonVersion>>() {
        });

        LogUtils.log(buildListener, "Number of releases: " + versions.size());

        for (KatalonVersion version : versions) {
            if ((version.getVersion().equals(versionNumber)) && (version.getOs().equalsIgnoreCase(os))) {
                String containingFolder = version.getContainingFolder();
                if (containingFolder == null) {
                    String fileName = version.getFilename();
                    String fileExtension = "";
                    if (fileName.endsWith(".zip")) {
                        fileExtension = ".zip";
                    } else if (fileName.endsWith(".tar.gz")) {
                        fileExtension = ".tar.gz";
                    }
                    containingFolder = fileName.replace(fileExtension, "");
                    version.setContainingFolder(containingFolder);
                }
                LogUtils.log(buildListener, "Katalon Studio is hosted at " + version.getUrl() + ".");
                return version;
            }
        }
        return null;
    }

    private static void downloadAndExtract(
            BuildListener buildListener, String versionNumber, File targetDir)
            throws IOException, InterruptedException {

        KatalonVersion version = KatalonUtils.getVersionInfo(buildListener, versionNumber);

        String versionUrl = version.getUrl();

        LogUtils.log(
                buildListener,
                "Downloading Katalon Studio from " + versionUrl + ". It may take a few minutes.");

        URL url = new URL(versionUrl);

        try (InputStream inputStream = url.openStream()) {
            Path temporaryFile = Files.createTempFile("Katalon-" + versionNumber, "");
            Files.copy(
                    inputStream,
                    temporaryFile,
                    StandardCopyOption.REPLACE_EXISTING);
            FilePath temporaryFilePath = new FilePath(temporaryFile.toFile());
            FilePath targetDirPath = new FilePath(targetDir);
            if (versionUrl.contains(".zip")) {
                temporaryFilePath.unzip(targetDirPath);
            } else if (versionUrl.contains(".tar.gz")) {
                temporaryFilePath.untar(targetDirPath, FilePath.TarCompression.GZIP);
            } else {
                throw new IllegalStateException();
            }
        }
    }

    private static File getKatalonFolder(String version) {
        String path = System.getProperty("user.home");

        Path p = Paths.get(path, ".katalon", version);
        return p.toFile();
    }

    static File getKatalonPackage(
            BuildListener buildListener, String versionNumber)
            throws IOException, InterruptedException {

        File katalonDir = getKatalonFolder(versionNumber);

        Path fileLog = Paths.get(katalonDir.toString(), ".katalon.done");

        if (fileLog.toFile().exists()) {
            LogUtils.log(buildListener, "Katalon Studio package has been downloaded already.");
        } else {
            FileUtils.deleteDirectory(katalonDir);

            boolean katalonDirCreated = katalonDir.mkdirs();
            if (!katalonDirCreated) {
                throw new IllegalStateException("Cannot create directory to store Katalon Studio package.");
            }

            KatalonUtils.downloadAndExtract(buildListener, versionNumber, katalonDir);

            boolean fileLogCreated = fileLog.toFile().createNewFile();
            if (fileLogCreated) {
                LogUtils.log(buildListener, "Katalon Studio has been installed successfully.");
            }
        }

        String[] childrenNames = katalonDir.list((dir, name) -> {
            File file = new File(dir, name);
            return file.isDirectory() && name.contains("Katalon");
        });

        String katalonContainingDirName = Arrays.stream(childrenNames).findFirst().get();


        File katalonContainingDir = new File(katalonDir, katalonContainingDirName);

        return katalonContainingDir;
    }

    private static boolean executeKatalon(
            BuildListener buildListener,
            String katalonExecutableFile,
            String projectPath,
            String executeArgs,
            String x11Display,
            String xvfbConfiguration)
            throws IOException, InterruptedException {
        File file = new File(katalonExecutableFile);
        if (!file.exists()) {
            file = new File(katalonExecutableFile + ".exe");
        }
        if (file.exists()) {
            file.setExecutable(true);
        }
        if (katalonExecutableFile.contains(" ")) {
            katalonExecutableFile = "\"" + katalonExecutableFile + "\"";
        }
        String command = katalonExecutableFile +
                " -noSplash " +
                " -runMode=console ";
        if (!executeArgs.contains("-projectPath")) {
            command += " -projectPath=\"" + projectPath + "\" ";
        }
        command += " " + executeArgs + " ";

        return OsUtils.runCommand(buildListener, command, x11Display, xvfbConfiguration);
    }

    public static boolean executeKatalon(
            BuildListener buildListener,
            String version,
            String location,
            String projectPath,
            String executeArgs,
            String x11Display,
            String xvfbConfiguration)
            throws IOException, InterruptedException {

        String katalonDirPath;

        if (StringUtils.isBlank(location)) {
            File katalonDir = KatalonUtils.getKatalonPackage(buildListener, version);
            katalonDirPath = katalonDir.getAbsolutePath();
        } else {
            katalonDirPath = location;
        }

        LogUtils.log(buildListener, "Using Katalon Studio at " + katalonDirPath);
        String katalonExecutableFile;
        String os = OsUtils.getOSVersion(buildListener);
        if (os.contains("macos")) {
            katalonExecutableFile = Paths.get(katalonDirPath, "Contents", "MacOS", "katalon")
                    .toAbsolutePath()
                    .toString();
        } else {
            katalonExecutableFile = Paths.get(katalonDirPath, "katalon")
                    .toAbsolutePath()
                    .toString();
        }
        return executeKatalon(
                buildListener,
                katalonExecutableFile,
                projectPath,
                executeArgs,
                x11Display,
                xvfbConfiguration);
    }
}
