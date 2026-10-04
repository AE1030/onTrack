// Cloudflare Worker that serves api.ontrackmac.ca by forwarding to Cloud Run.
//
// Why it exists: Cloud Run picks the service from the Host header, and it only knows its own
// run.app name. The free Cloudflare plan cannot rewrite the Host header with an Origin Rule, but
// a Worker's fetch() sets Host from the URL it calls, so this does the same job for free.
//
// Deploy: Cloudflare dashboard > Workers & Pages > Create > Worker, paste this file, Deploy, then
// Settings > Domains & Routes > Add > Custom domain > api.ontrackmac.ca.
// The old `api` DNS record must be deleted first. The custom domain creates its own.

const ORIGIN = "ontrack-backend-905622287446.us-east5.run.app";

export default {
  async fetch(request) {
    const incoming = new URL(request.url);

    const target = new URL(request.url);
    target.protocol = "https:";
    target.hostname = ORIGIN;
    target.port = "";

    // Building from the original request keeps the method, body and headers as they were.
    const upstream = new Request(target, request);
    upstream.headers.set("x-forwarded-host", incoming.hostname);
    // The backend only sees the Worker's address as the caller, so pass the real client along.
    upstream.headers.set("x-client-ip", request.headers.get("cf-connecting-ip") ?? "");

    // Hand redirects to the browser instead of following them inside the Worker.
    return fetch(upstream, { redirect: "manual" });
  },
};
