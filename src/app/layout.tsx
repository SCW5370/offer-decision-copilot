import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Offer 决策工作台",
  description:
    "围绕机会比较、公开信号和核验清单设计的 Offer 决策工作台，帮助你更稳妥地做出选择。",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
