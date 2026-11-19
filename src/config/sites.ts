import type { KioskTab } from '../types/kiosk';

/**
 * Default sites shown as tabs at the top of the kiosk.
 * Add, edit, or remove entries here (or configure them at runtime via the ⚙ button).
 * Both IP addresses and `.local` mDNS hostnames (resolved natively on Android) are supported.
 */
export const DEFAULT_SITES: KioskTab[] = [
  {
    id: 'site-1',
    title: 'Farz',
    url: 'http://pi-klipper.local',
  },
  {
    id: 'site-2',
    title: 'Klip',
    url: 'http://pi-klipper2.local',
  },
];

