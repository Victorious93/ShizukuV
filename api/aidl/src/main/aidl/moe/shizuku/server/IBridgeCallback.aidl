package moe.shizuku.server;

// Victorious: result delivery for IShizukuService.bridgeSubmit.
interface IBridgeCallback {

    oneway void onResult(in Bundle result) = 1;
}
