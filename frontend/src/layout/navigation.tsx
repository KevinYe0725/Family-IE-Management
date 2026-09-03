import {
  IconBell,
  IconApartment,
  IconBriefcase,
  IconCoinMoney,
  IconCreditCard,
  IconHome,
  IconList,
  IconSetting,
  IconUserGroup
} from '@douyinfe/semi-icons';
import type { HouseholdRole } from '../api/contracts';

export interface NavigationItem {
  key: string;
  label: string;
  path: string;
  icon: typeof IconHome;
  description: string;
}

export const railItems: NavigationItem[] = [
  { key: 'overview', label: '总览', path: '/workspace/overview', icon: IconHome, description: '家庭财务总览' },
  { key: 'ledger', label: '收支', path: '/workspace/transactions', icon: IconList, description: '日常收支和预算' },
  { key: 'assets', label: '资产', path: '/workspace/assets', icon: IconApartment, description: '家庭资产账户' },
  { key: 'investments', label: '投资', path: '/workspace/investments', icon: IconBriefcase, description: '投资持仓与行情' },
  { key: 'loans', label: '贷款', path: '/workspace/loans', icon: IconCreditCard, description: '家庭贷款计划' },
  { key: 'settings', label: '设置', path: '/workspace/settings', icon: IconSetting, description: '家庭与系统设置' }
];

export const moduleItems: NavigationItem[] = [
  { key: 'overview', label: '家庭总览', path: '/workspace/overview', icon: IconHome, description: '查看家庭财务摘要' },
  { key: 'transactions', label: '收支明细', path: '/workspace/transactions', icon: IconList, description: '查询与整理日常收支' },
  { key: 'budgets', label: '预算管理', path: '/workspace/budgets', icon: IconCoinMoney, description: '设置和检查家庭预算' },
  { key: 'recurring', label: '周期账单', path: '/workspace/recurring', icon: IconBell, description: '查看待确认的周期账单' },
  { key: 'assets', label: '资产账户', path: '/workspace/assets', icon: IconApartment, description: '管理房产、车辆和其他资产' },
  { key: 'investments', label: '投资持仓', path: '/workspace/investments', icon: IconBriefcase, description: '查看证券账户与持仓' },
  { key: 'loans', label: '贷款计划', path: '/workspace/loans', icon: IconCreditCard, description: '查看还款计划' },
  { key: 'notifications', label: '提醒中心', path: '/workspace/notifications', icon: IconBell, description: '处理家庭财务提醒' },
  { key: 'family', label: '家庭与成员', path: '/workspace/family', icon: IconUserGroup, description: '查看家庭成员和角色' },
  { key: 'settings', label: '系统设置', path: '/workspace/settings', icon: IconSetting, description: '账号与工作区偏好' }
];

export const pageMeta: Record<string, { title: string; eyebrow: string; description: string }> = Object.fromEntries(
  moduleItems.map(item => [item.path, {
    title: item.label,
    eyebrow: item.key === 'overview' ? '今天的家庭财务' : '家账工作区',
    description: item.description
  }])
);

export function roleLabel(role: HouseholdRole): string {
  return role === 'OWNER' ? '家庭所有者' : role === 'ADMIN' ? '家庭管理员' : '家庭成员';
}

export function canManage(role: HouseholdRole): boolean {
  return role === 'OWNER' || role === 'ADMIN';
}
