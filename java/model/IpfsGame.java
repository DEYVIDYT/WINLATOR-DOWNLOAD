package com.winlator.Download.model;

public class IpfsGame {
    private int id;
    private String gameName;
    private String ipfsHash;
    private String originalFilename;
    private long fileSize; // Size in bytes
    private String uploadTimestamp;

    public IpfsGame(int id, String gameName, String ipfsHash, String originalFilename, long fileSize, String uploadTimestamp) {
        this.id = id;
        this.gameName = gameName;
        this.ipfsHash = ipfsHash;
        this.originalFilename = originalFilename;
        this.fileSize = fileSize;
        this.uploadTimestamp = uploadTimestamp;
    }

    // Getters
    public int getId() { return id; }
    public String getGameName() { return gameName; }
    public String getIpfsHash() { return ipfsHash; }
    public String getOriginalFilename() { return originalFilename; }
    public long getFileSize() { return fileSize; }
    public String getUploadTimestamp() { return uploadTimestamp; }

    // Helper to format file size for display
    public String getFormattedFileSize() {
        if (fileSize < 1024) return fileSize + " B";
        int exp = (int) (Math.log(fileSize) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + ""; // Kilo, Mega, Giga, Tera, Peta, Exa
        return String.format("%.1f %sB", fileSize / Math.pow(1024, exp), pre);
    }
}
