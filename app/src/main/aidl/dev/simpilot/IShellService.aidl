package dev.simpilot;

interface IShellService {
    String setDefault(String role, int subId) = 1;
    String describeBackend() = 2;
    void destroy() = 16777114;
}
