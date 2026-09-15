package dev.simpilot;

interface IShellService {
    String setDefault(String role, int subId) = 1;
    String describeBackend() = 2;
    String mobileUsage(int subId, long startTime, long endTime) = 3;
    void destroy() = 16777114;
}
