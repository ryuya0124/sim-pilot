package dev.simpilot;

interface IShellService {
    String setDefault(int transactionCode, int subId) = 1;
    void destroy() = 16777114;
}
