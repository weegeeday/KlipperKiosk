import { Capacitor, registerPlugin } from '@capacitor/core';

export interface HostResolveOptions {
  host: string;
  scheme?: string;
  port?: number;
  path?: string;
}

export interface HostResolveResult {
  url: string;
  ip: string;
  source: 'mdns' | 'dns' | string;
  host: string;
}

interface HostResolverPlugin {
  resolve(options: HostResolveOptions): Promise<HostResolveResult>;
}

const HostResolver = registerPlugin<HostResolverPlugin>('HostResolver');

const IPV4_REGEX = /^(\d{1,3}\.){3}\d{1,3}$/;
const HOST_CACHE_KEY = 'klipperkiosk-host-cache-v1';

function readHostCache(): Record<string, string> {
  try {
    const raw = localStorage.getItem(HOST_CACHE_KEY);
    return raw ? (JSON.parse(raw) as Record<string, string>) : {};
  } catch {
    return {};
  }
}

function writeHostCache(host: string, ip: string) {
  try {
    const cache = readHostCache();
    cache[host] = ip;
    localStorage.setItem(HOST_CACHE_KEY, JSON.stringify(cache));
  } catch {
    // Ignore storage errors
  }
}

export function getCachedSiteUrl(rawUrl: string): string | null {
  if (!Capacitor.isNativePlatform()) return rawUrl;
  try {
    const parsed = new URL(rawUrl);
    const host = parsed.hostname;
    if (!host || IPV4_REGEX.test(host) || host === 'localhost') {
      return rawUrl;
    }
    const cachedIp = readHostCache()[host];
    if (!cachedIp) return null;
    const scheme = parsed.protocol.replace(/:$/, '') || 'http';
    const port = parsed.port ? Number(parsed.port) : scheme === 'https' ? 443 : 80;
    const isDefaultPort =
      (scheme === 'http' && port === 80) || (scheme === 'https' && port === 443);
    const portPart = isDefaultPort ? '' : `:${port}`;
    return `${scheme}://${cachedIp}${portPart}${parsed.pathname}${parsed.search}${parsed.hash}`;
  } catch {
    return rawUrl;
  }
}

export interface ResolvedUrlInfo {
  url: string;
  ip: string | null;
  source: string | null;
}

export async function resolveSiteUrl(rawUrl: string): Promise<ResolvedUrlInfo> {
  if (!Capacitor.isNativePlatform()) {
    return { url: rawUrl, ip: null, source: null };
  }

  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return { url: rawUrl, ip: null, source: null };
  }

  const host = parsed.hostname;
  if (!host || IPV4_REGEX.test(host) || host === 'localhost') {
    return { url: rawUrl, ip: host || null, source: 'direct' };
  }

  const scheme = parsed.protocol.replace(/:$/, '') || 'http';
  const port = parsed.port
    ? Number(parsed.port)
    : scheme === 'https'
      ? 443
      : 80;
  const path = `${parsed.pathname}${parsed.search}${parsed.hash}`;

  try {
    const result = await HostResolver.resolve({ host, scheme, port, path });
    if (result.ip) {
      writeHostCache(host, result.ip);
    }
    return {
      url: result.url || rawUrl,
      ip: result.ip ?? null,
      source: result.source ?? null,
    };
  } catch {
    return { url: getCachedSiteUrl(rawUrl) ?? rawUrl, ip: null, source: null };
  }
}
