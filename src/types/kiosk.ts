export interface KioskTab {
  id: string;
  title: string;
  url: string;
}

export interface KioskState {
  tabs: KioskTab[];
  activeTabId: string;
  kioskLocked: boolean;
}

