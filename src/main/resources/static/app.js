// SuperBizAgent 前端应用
class SuperBizAgentApp {
    constructor() {
        window._app = this;
        this.apiBaseUrl = `${window.location.origin}/api`;
        this.currentMode = 'quick'; // 'quick' 或 'stream'
        this.sessionId = this.generateSessionId();
        this.isStreaming = false;
        this.currentChatHistory = []; // 当前对话的消息历史
        this.chatHistories = this.loadChatHistories(); // 所有历史对话
        this.isCurrentChatFromHistory = false; // 标记当前对话是否是从历史记录加载的
        this.knowledgeFiles = [];

        this.initializeElements();
        this.bindEvents();
        this.updateUI();
        this.initMarkdown();
        this.initTheme();
        this.checkAndSetCentered();
        this.renderChatHistory();
        this.loadKnowledgeFiles();
    }

    // 初始化Markdown配置
    initMarkdown() {
        // 等待 marked 库加载完成
        const checkMarked = () => {
            if (typeof marked !== 'undefined') {
                try {
                    // 配置marked选项
                    marked.setOptions({
                        breaks: true,  // 支持GFM换行
                        gfm: true,     // 启用GitHub风格的Markdown
                        headerIds: false,
                        mangle: false
                    });

                    // 配置代码高亮
                    if (typeof hljs !== 'undefined') {
                        marked.setOptions({
                            highlight: function(code, lang) {
                                if (lang && hljs.getLanguage(lang)) {
                                    try {
                                        return hljs.highlight(code, { language: lang }).value;
                                    } catch (err) {
                                        console.error('代码高亮失败:', err);
                                    }
                                }
                                return code;
                            }
                        });
                    }
                    console.log('Markdown 渲染库初始化成功');
                } catch (e) {
                    console.error('Markdown 配置失败:', e);
                }
            } else {
                // 如果 marked 还没加载，等待一段时间后重试
                setTimeout(checkMarked, 100);
            }
        };
        checkMarked();
    }

    // 安全地渲染 Markdown
    renderMarkdown(content) {
        if (!content) return '';
        
        // 检查 marked 是否可用
        if (typeof marked === 'undefined') {
            console.warn('marked 库未加载，使用纯文本显示');
            return this.escapeHtml(content);
        }
        
        try {
            const html = marked.parse(content);
            if (typeof DOMPurify !== 'undefined') {
                return DOMPurify.sanitize(html);
            }
            return this.escapeHtml(content);
        } catch (e) {
            console.error('Markdown 渲染失败:', e);
            return this.escapeHtml(content);
        }
    }

    // 高亮代码块
    highlightCodeBlocks(container) {
        if (typeof hljs !== 'undefined' && container) {
            try {
                container.querySelectorAll('pre code').forEach((block) => {
                    if (!block.classList.contains('hljs')) {
                        hljs.highlightElement(block);
                    }
                });
            } catch (e) {
                console.error('代码高亮失败:', e);
            }
        }
    }

    // 初始化DOM元素
    initializeElements() {
        // 侧边栏元素
        this.sidebar = document.querySelector('.sidebar');
        this.newChatBtn = document.getElementById('newChatBtn');
        this.knowledgeUploadBtn = document.getElementById('knowledgeUploadBtn');
        this.refreshKnowledgeBtn = document.getElementById('refreshKnowledgeBtn');
        this.knowledgeFilesList = document.getElementById('knowledgeFilesList');
        this.backToChatBtn = document.getElementById('backToChatBtn');
        this.knowledgeUploadMainBtn = document.getElementById('knowledgeUploadMainBtn');
        this.knowledgeSearchInput = document.getElementById('knowledgeSearchInput');
        this.knowledgeTotalCount = document.getElementById('knowledgeTotalCount');
        this.knowledgeIndexableCount = document.getElementById('knowledgeIndexableCount');
        this.knowledgeTotalSize = document.getElementById('knowledgeTotalSize');
        this.aiOpsSidebarBtn = document.getElementById('aiOpsSidebarBtn');
        this.sidebarToggle = document.getElementById('sidebarToggle');
        this.sidebarOverlay = document.getElementById('sidebarOverlay');
        this.sidebarChatContent = document.getElementById('sidebarChatContent');

        // Navigation tabs
        this.navTabChat = document.getElementById('navTabChat');
        this.navTabKnowledge = document.getElementById('navTabKnowledge');
        this.navTabSkills = document.getElementById('navTabSkills');
        this.navTabSettings = document.getElementById('navTabSettings');

        // Page containers
        this.chatContainer = document.getElementById('chatContainer');
        this.knowledgeContainer = document.getElementById('knowledgeContainer');
        this.skillsContainer = document.getElementById('skillsContainer');
        this.settingsContainer = document.getElementById('settingsContainer');
        this.themeToggleCheckbox = document.getElementById('themeToggleCheckbox');

        // 输入区域元素
        this.messageInput = document.getElementById('messageInput');
        this.sendButton = document.getElementById('sendButton');
        this.toolsBtn = document.getElementById('toolsBtn');
        this.toolsMenu = document.getElementById('toolsMenu');
        this.uploadFileItem = document.getElementById('uploadFileItem');
        this.modeSelectorBtn = document.getElementById('modeSelectorBtn');
        this.modeDropdown = document.getElementById('modeDropdown');
        this.currentModeText = document.getElementById('currentModeText');
        this.fileInput = document.getElementById('fileInput');

        // 聊天区域元素
        this.chatMessages = document.getElementById('chatMessages');
        this.loadingOverlay = document.getElementById('loadingOverlay');
        this.welcomeGreeting = document.getElementById('welcomeGreeting');
        this.chatHistoryList = document.getElementById('chatHistoryList');

        // Knowledge page elements
        this.knowledgeDropZone = document.getElementById('knowledgeDropZone');
        this.knowledgeUrlInput = document.getElementById('knowledgeUrlInput');
        this.knowledgeUrlTitle = document.getElementById('knowledgeUrlTitle');
        this.knowledgeUrlSubmit = document.getElementById('knowledgeUrlSubmit');
        this.knowledgeFileInput = document.getElementById('knowledgeFileInput');

        // Skills page elements
        this.skillsGrid = document.getElementById('skillsGrid');
        this.skillSearchInput = document.getElementById('skillSearchInput');
        this.skillSearchBtn = document.getElementById('skillSearchBtn');
        this.skillCategoryFilter = document.getElementById('skillCategoryFilter');
        this.skillGithubSearchBtn = document.getElementById('skillGithubSearchBtn');
        this.skillCreateBtn = document.getElementById('skillCreateBtn');
        this.githubSearchModal = document.getElementById('githubSearchModal');
        this.githubSearchInput = document.getElementById('githubSearchInput');
        this.githubSearchSubmit = document.getElementById('githubSearchSubmit');
        this.githubSearchResults = document.getElementById('githubSearchResults');
        this.githubSearchClose = document.getElementById('githubSearchClose');
        this.skillCreateModal = document.getElementById('skillCreateModal');
        this.skillCreateClose = document.getElementById('skillCreateClose');
        this.skillFormSubmit = document.getElementById('skillFormSubmit');
        this.skillDetailModal = document.getElementById('skillDetailModal');
        this.skillDetailClose = document.getElementById('skillDetailClose');

        // Confirm modal
        this.confirmModal = document.getElementById('confirmModal');
        this.confirmModalMessage = document.getElementById('confirmModalMessage');
        this.confirmModalCancel = document.getElementById('confirmModalCancel');
        this.confirmModalOK = document.getElementById('confirmModalOK');

        // 初始化时检查是否需要居中
        this.checkAndSetCentered();
    }

    // 绑定事件监听器
    bindEvents() {
        // Navigation tabs
        if (this.navTabChat) this.navTabChat.addEventListener('click', () => this.switchPage('chat'));
        if (this.navTabKnowledge) this.navTabKnowledge.addEventListener('click', () => this.switchPage('knowledge'));
        if (this.navTabSkills) this.navTabSkills.addEventListener('click', () => this.switchPage('skills'));
        if (this.navTabSettings) this.navTabSettings.addEventListener('click', () => this.switchPage('settings'));

        // Theme toggle
        if (this.themeToggleCheckbox) {
            this.themeToggleCheckbox.addEventListener('change', () => this.toggleTheme());
        }

        // 新建对话
        if (this.newChatBtn) {
            this.newChatBtn.addEventListener('click', () => this.newChat());
        }

        if (this.knowledgeUploadBtn) {
            this.knowledgeUploadBtn.addEventListener('click', () => this.knowledgeFileInput?.click());
        }

        if (this.refreshKnowledgeBtn) {
            this.refreshKnowledgeBtn.addEventListener('click', () => this.loadKnowledgeFiles());
        }

        if (this.knowledgeUploadMainBtn) {
            this.knowledgeUploadMainBtn.addEventListener('click', () => this.knowledgeFileInput?.click());
        }

        if (this.knowledgeSearchInput) {
            this.knowledgeSearchInput.addEventListener('input', () => this.renderKnowledgeFiles(this.knowledgeFiles));
        }

        // Knowledge file input
        if (this.knowledgeFileInput) {
            this.knowledgeFileInput.addEventListener('change', (e) => this.handleKnowledgeFileUpload(e));
        }

        // Knowledge drop zone
        if (this.knowledgeDropZone) {
            this.knowledgeDropZone.addEventListener('dragover', (e) => { e.preventDefault(); });
            this.knowledgeDropZone.addEventListener('drop', (e) => {
                e.preventDefault();
                const files = e.dataTransfer.files;
                if (files.length > 0) this.uploadKnowledgeFile(files[0]);
            });
        }

        // URL import
        if (this.knowledgeUrlSubmit) {
            this.knowledgeUrlSubmit.addEventListener('click', () => this.ingestUrl());
        }

        // Skills events
        if (this.skillSearchBtn) this.skillSearchBtn.addEventListener('click', () => this.searchSkills());
        if (this.skillSearchInput) this.skillSearchInput.addEventListener('keypress', (e) => { if (e.key === 'Enter') this.searchSkills(); });
        if (this.skillCategoryFilter) this.skillCategoryFilter.addEventListener('change', () => this.loadSkills());
        if (this.skillGithubSearchBtn) this.skillGithubSearchBtn.addEventListener('click', () => this.openGithubSearchModal());
        if (this.githubSearchClose) this.githubSearchClose.addEventListener('click', () => this.closeGithubSearchModal());
        if (this.githubSearchSubmit) this.githubSearchSubmit.addEventListener('click', () => this.searchGithubSkills());
        if (this.githubSearchInput) this.githubSearchInput.addEventListener('keypress', (e) => { if (e.key === 'Enter') this.searchGithubSkills(); });
        if (this.skillCreateBtn) this.skillCreateBtn.addEventListener('click', () => this.openCreateSkillModal());
        if (this.skillCreateClose) this.skillCreateClose.addEventListener('click', () => this.closeCreateSkillModal());
        if (this.skillFormSubmit) this.skillFormSubmit.addEventListener('click', () => this.createSkill());
        if (this.skillDetailClose) this.skillDetailClose.addEventListener('click', () => this.closeSkillDetailModal());

        // Modal backdrop close
        [this.githubSearchModal, this.skillCreateModal, this.skillDetailModal].forEach(modal => {
            if (modal) modal.addEventListener('click', (e) => { if (e.target === modal) modal.style.display = 'none'; });
        });

        // Mobile sidebar
        if (this.sidebarToggle) this.sidebarToggle.addEventListener('click', () => {
            this.sidebar?.classList.toggle('open');
            this.sidebarOverlay?.classList.toggle('open');
        });
        if (this.sidebarOverlay) this.sidebarOverlay.addEventListener('click', () => {
            this.sidebar?.classList.remove('open');
            this.sidebarOverlay?.classList.remove('open');
        });
        
        // 模式选择下拉菜单
        if (this.modeSelectorBtn) {
            this.modeSelectorBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.toggleModeDropdown();
            });
        }
        
        // 下拉菜单项点击
        const dropdownItems = document.querySelectorAll('.dropdown-item');
        dropdownItems.forEach(item => {
            item.addEventListener('click', (e) => {
                const mode = item.getAttribute('data-mode');
                this.selectMode(mode);
                this.closeModeDropdown();
            });
        });
        
        // 点击外部关闭下拉菜单
        document.addEventListener('click', (e) => {
            if (!this.modeSelectorBtn.contains(e.target) && 
                !this.modeDropdown.contains(e.target)) {
                this.closeModeDropdown();
            }
        });
        
        // 发送消息
        if (this.sendButton) {
            this.sendButton.addEventListener('click', () => this.sendMessage());
        }
        
        if (this.messageInput) {
            this.messageInput.addEventListener('keypress', (e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    this.sendMessage();
                }
            });
        }
        
        // 工具按钮和菜单
        if (this.toolsBtn) {
            this.toolsBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.toggleToolsMenu();
            });
        }
        
        // 工具菜单项点击事件
        if (this.uploadFileItem) {
            this.uploadFileItem.addEventListener('click', () => {
                if (this.fileInput) {
                    this.fileInput.click();
                }
                this.closeToolsMenu();
            });
        }
        
        // 点击外部关闭工具菜单
        document.addEventListener('click', (e) => {
            if (this.toolsBtn && this.toolsMenu && 
                !this.toolsBtn.contains(e.target) && 
                !this.toolsMenu.contains(e.target)) {
                this.closeToolsMenu();
            }
        });
        
        if (this.fileInput) {
            this.fileInput.addEventListener('change', (e) => this.handleFileSelect(e));
        }
    }

    switchPage(page) {
        // Update nav tabs
        [this.navTabChat, this.navTabKnowledge, this.navTabSkills, this.navTabSettings].forEach(tab => {
            if (tab) tab.classList.toggle('active', tab.dataset.page === page);
        });

        // Show/hide containers
        const containers = {
            chat: this.chatContainer,
            knowledge: this.knowledgeContainer,
            skills: this.skillsContainer,
            settings: this.settingsContainer
        };
        Object.entries(containers).forEach(([key, el]) => {
            if (el) el.style.display = (key === page) ? 'flex' : 'none';
        });

        // Toggle sidebar chat content (only show for chat page)
        if (this.sidebarChatContent) {
            this.sidebarChatContent.style.display = page === 'chat' ? 'block' : 'none';
        }

        // Toggle AI Ops button
        if (this.aiOpsSidebarBtn) {
            this.aiOpsSidebarBtn.style.display = page === 'chat' ? 'flex' : 'none';
        }

        if (page === 'knowledge') this.loadKnowledgeFiles();
        if (page === 'skills') this.loadSkills();
        if (page === 'chat') this.checkAndSetCentered();
    }

    showKnowledgePage() {
        this.switchPage('knowledge');
    }

    showChatPage() {
        this.switchPage('chat');
    }

    isKnowledgePageVisible() {
        return this.knowledgeContainer && this.knowledgeContainer.style.display !== 'none';
    }

    // 切换工具菜单显示/隐藏
    toggleToolsMenu() {
        if (this.toolsMenu && this.toolsBtn) {
            const wrapper = this.toolsBtn.closest('.tools-btn-wrapper');
            if (wrapper) {
                wrapper.classList.toggle('active');
            }
        }
    }

    // 关闭工具菜单
    closeToolsMenu() {
        if (this.toolsMenu && this.toolsBtn) {
            const wrapper = this.toolsBtn.closest('.tools-btn-wrapper');
            if (wrapper) {
                wrapper.classList.remove('active');
            }
        }
    }

    // 新建对话
    newChat() {
        this.showChatPage();
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成后再新建对话', 'warning');
            return;
        }
        
        // 如果当前有对话内容，且不是从历史记录加载的，才保存为新的历史对话
        // 如果是从历史记录加载的，只需要更新该历史记录
        if (this.currentChatHistory.length > 0) {
            if (this.isCurrentChatFromHistory) {
                // 当前对话是从历史记录加载的，更新该历史记录
                this.updateCurrentChatHistory();
            } else {
                // 当前对话是新对话，保存为新的历史对话
                this.saveCurrentChat();
            }
        }
        
        // 停止所有进行中的操作
        this.isStreaming = false;
        
        // 清空输入框
        if (this.messageInput) {
            this.messageInput.value = '';
        }
        
        // 清空当前对话历史
        this.currentChatHistory = [];
        
        // 重置标记
        this.isCurrentChatFromHistory = false;
        
        // 清空聊天记录
        if (this.chatMessages) {
            this.chatMessages.innerHTML = '';
        }
        
        // 生成新的会话ID
        this.sessionId = this.generateSessionId();
        
        // 重置模式为快速
        this.currentMode = 'quick';
        this.updateUI();
        
        // 重新设置居中样式（确保对话框居中显示）
        this.checkAndSetCentered();
        
        // 确保容器有过渡动画
        if (this.chatContainer) {
            this.chatContainer.style.transition = 'all 0.5s ease';
        }
        
        // 更新历史对话列表
        this.renderChatHistory();
    }
    
    // 保存当前对话到历史记录（新建）
    saveCurrentChat() {
        if (this.currentChatHistory.length === 0) {
            return;
        }
        
        // 检查是否已存在相同ID的历史记录
        const existingIndex = this.chatHistories.findIndex(h => h.id === this.sessionId);
        if (existingIndex !== -1) {
            // 如果已存在，更新而不是新建
            this.updateCurrentChatHistory();
            return;
        }
        
        // 获取对话标题（使用第一条用户消息的前30个字符）
        const firstUserMessage = this.currentChatHistory.find(msg => msg.type === 'user');
        const title = firstUserMessage ? 
            (firstUserMessage.content.substring(0, 30) + (firstUserMessage.content.length > 30 ? '...' : '')) : 
            '新对话';
        
        const chatHistory = {
            id: this.sessionId,
            title: title,
            messages: [...this.currentChatHistory],
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
        };
        
        // 添加到历史记录列表的开头
        this.chatHistories.unshift(chatHistory);
        
        // 限制历史记录数量（最多保存50条）
        if (this.chatHistories.length > 50) {
            this.chatHistories = this.chatHistories.slice(0, 50);
        }
        
        // 保存到localStorage
        this.saveChatHistories();
    }
    
    // 更新当前对话的历史记录
    updateCurrentChatHistory() {
        if (this.currentChatHistory.length === 0) {
            return;
        }
        
        const existingIndex = this.chatHistories.findIndex(h => h.id === this.sessionId);
        if (existingIndex === -1) {
            // 如果不存在，调用保存方法
            this.saveCurrentChat();
            return;
        }
        
        // 更新现有的历史记录
        const history = this.chatHistories[existingIndex];
        history.messages = [...this.currentChatHistory];
        history.updatedAt = new Date().toISOString();
        
        // 如果标题需要更新（第一条消息改变了）
        const firstUserMessage = this.currentChatHistory.find(msg => msg.type === 'user');
        if (firstUserMessage) {
            const newTitle = firstUserMessage.content.substring(0, 30) + (firstUserMessage.content.length > 30 ? '...' : '');
            if (history.title !== newTitle) {
                history.title = newTitle;
            }
        }
        
        // 保存到localStorage
        this.saveChatHistories();
    }
    
    // 加载历史对话列表
    loadChatHistories() {
        try {
            const stored = localStorage.getItem('chatHistories');
            return stored ? JSON.parse(stored) : [];
        } catch (e) {
            console.error('加载历史对话失败:', e);
            return [];
        }
    }
    
    // 保存历史对话列表到localStorage
    saveChatHistories() {
        try {
            localStorage.setItem('chatHistories', JSON.stringify(this.chatHistories));
        } catch (e) {
            console.error('保存历史对话失败:', e);
        }
    }
    
    // 渲染历史对话列表
    renderChatHistory() {
        if (!this.chatHistoryList) {
            return;
        }
        
        this.chatHistoryList.innerHTML = '';
        
        if (this.chatHistories.length === 0) {
            return;
        }
        
        this.chatHistories.forEach((history, index) => {
            const historyItem = document.createElement('div');
            historyItem.className = 'history-item';
            historyItem.dataset.historyId = history.id;
            
            historyItem.innerHTML = `
                <div class="history-item-content">
                    <span class="history-item-title">${this.escapeHtml(history.title)}</span>
                </div>
                <button class="history-item-delete" data-history-id="${history.id}" title="删除">
                    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <path d="M18 6L6 18M6 6L18 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                    </svg>
                </button>
            `;
            
            // 点击历史项加载对话
            historyItem.addEventListener('click', (e) => {
                if (!e.target.closest('.history-item-delete')) {
                    this.loadChatHistory(history.id);
                }
            });
            
            // 删除历史对话
            const deleteBtn = historyItem.querySelector('.history-item-delete');
            deleteBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.deleteChatHistory(history.id);
            });
            
            this.chatHistoryList.appendChild(historyItem);
        });
    }
    
    // 加载历史对话
    loadChatHistory(historyId) {
        const history = this.chatHistories.find(h => h.id === historyId);
        if (!history) {
            return;
        }
        
        // 如果当前有对话内容，且不是同一个对话，先保存
        if (this.currentChatHistory.length > 0 && this.sessionId !== historyId) {
            if (this.isCurrentChatFromHistory) {
                // 如果当前对话也是从历史记录加载的，更新它
                this.updateCurrentChatHistory();
            } else {
                // 如果当前对话是新对话，保存为新历史
                this.saveCurrentChat();
            }
        }
        
        // 加载历史对话
        this.sessionId = history.id;
        this.currentChatHistory = [...history.messages];
        this.isCurrentChatFromHistory = true; // 标记为从历史记录加载
        
        // 清空并重新渲染消息
        if (this.chatMessages) {
            this.chatMessages.innerHTML = '';
            history.messages.forEach(msg => {
                this.addMessage(msg.type, msg.content, false, false); // false表示不是流式，false表示不保存到历史（因为已经存在）
            });
        }
        
        // 更新UI
        this.checkAndSetCentered();
        this.renderChatHistory();
    }
    
    // 删除历史对话
    deleteChatHistory(historyId) {
        this.chatHistories = this.chatHistories.filter(h => h.id !== historyId);
        this.saveChatHistories();
        this.renderChatHistory();
        
        // 如果删除的是当前对话，清空当前对话
        if (this.sessionId === historyId) {
            this.currentChatHistory = [];
            if (this.chatMessages) {
                this.chatMessages.innerHTML = '';
            }
            this.sessionId = this.generateSessionId();
            this.checkAndSetCentered();
        }
    }

    // 切换模式下拉菜单
    toggleModeDropdown() {
        if (this.modeSelectorBtn && this.modeDropdown) {
            const wrapper = this.modeSelectorBtn.closest('.mode-selector-wrapper');
            if (wrapper) {
                wrapper.classList.toggle('active');
            }
        }
    }

    // 关闭模式下拉菜单
    closeModeDropdown() {
        if (this.modeSelectorBtn && this.modeDropdown) {
            const wrapper = this.modeSelectorBtn.closest('.mode-selector-wrapper');
            if (wrapper) {
                wrapper.classList.remove('active');
            }
        }
    }

    // 选择模式
    selectMode(mode) {
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成后再切换模式', 'warning');
            return;
        }
        
        this.currentMode = mode;
        this.updateUI();
        
        const modeNames = {
            'quick': '快速',
            'stream': '流式'
        };
        
        this.showNotification(`已切换到${modeNames[mode]}模式`, 'info');
    }

    // 更新UI
    updateUI() {
        // 更新模式选择器显示
        if (this.currentModeText) {
            const modeNames = {
                'quick': '快速',
                'stream': '流式'
            };
            this.currentModeText.textContent = modeNames[this.currentMode] || '快速';
        }
        
        // 更新下拉菜单选中状态
        const dropdownItems = document.querySelectorAll('.dropdown-item');
        dropdownItems.forEach(item => {
            const mode = item.getAttribute('data-mode');
            if (mode === this.currentMode) {
                item.classList.add('active');
            } else {
                item.classList.remove('active');
            }
        });
        
        // 更新发送按钮状态
        if (this.sendButton) {
            this.sendButton.disabled = this.isStreaming;
        }
        
        // 更新输入框状态
        if (this.messageInput) {
            this.messageInput.disabled = this.isStreaming;
            this.messageInput.placeholder = '问问智能OnCall助手';
        }
    }

    // 生成随机会话ID
    generateSessionId() {
        return 'session_' + Math.random().toString(36).substr(2, 9) + '_' + Date.now();
    }

    // 发送消息
    async sendMessage() {
        let message = '';
        if (this.messageInput) {
            message = this.messageInput.value.trim();
        }
        
        if (!message) {
            this.showNotification('请输入消息内容', 'warning');
            return;
        }

        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成', 'warning');
            return;
        }

        // 显示用户消息
        this.addMessage('user', message);
        
        // 清空输入框
        if (this.messageInput) {
            this.messageInput.value = '';
        }

        // 设置发送状态
        this.isStreaming = true;
        this.updateUI();

        try {
            if (this.currentMode === 'quick') {
                await this.sendQuickMessage(message);
            } else if (this.currentMode === 'stream') {
                await this.sendStreamMessage(message);
            }
        } catch (error) {
            console.error('发送消息失败:', error);
            this.addMessage('assistant', '抱歉，发送消息时出现错误：' + error.message);
        } finally {
            this.isStreaming = false;
            this.updateUI();
            
            // 如果当前对话是从历史记录加载的，更新历史记录
            if (this.isCurrentChatFromHistory && this.currentChatHistory.length > 0) {
                this.updateCurrentChatHistory();
                this.renderChatHistory(); // 更新历史对话列表显示
            }
        }
    }

    // 发送快速消息（普通对话）
    async sendQuickMessage(message) {
        // 添加等待提示消息
        const loadingMessage = this.addLoadingMessage('正在思考...');
        
        try {
            const response = await fetch(`${this.apiBaseUrl}/chat`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    Id: this.sessionId,
                    Question: message
                })
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            const data = await response.json();
            console.log('[sendQuickMessage] 响应数据:', JSON.stringify(data));
            
            // 移除等待提示消息
            if (loadingMessage && loadingMessage.parentNode) {
                loadingMessage.parentNode.removeChild(loadingMessage);
            }
            
            // 统一响应格式：检查 data.code 或 data.message 判断请求是否成功
            if (data.code === 200 || data.message === 'success') {
                // data.data 是 ChatResponse 对象
                const chatResponse = data.data;
                
                if (chatResponse && chatResponse.success) {
                    // 成功：添加实际响应消息（即使 answer 为空也显示）
                    const answer = chatResponse.answer || '（无回复内容）';
                    this.addMessage('assistant', answer);
                } else if (chatResponse && chatResponse.errorMessage) {
                    // 业务错误
                    throw new Error(chatResponse.errorMessage);
                } else {
                    // 兜底：尝试显示任何可用内容
                    const fallbackAnswer = chatResponse?.answer || chatResponse?.errorMessage || '服务返回了空内容';
                    this.addMessage('assistant', fallbackAnswer);
                }
            } else {
                // HTTP 成功但业务失败
                throw new Error(data.message || '请求失败');
            }
        } catch (error) {
            // 出错时也要移除等待提示消息
            if (loadingMessage && loadingMessage.parentNode) {
                loadingMessage.parentNode.removeChild(loadingMessage);
            }
            throw error;
        }
    }

    // 发送流式消息
    async sendStreamMessage(message) {
        try {
            const response = await fetch(`${this.apiBaseUrl}/chat_stream`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    Id: this.sessionId,
                    Question: message
                })
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }
            
            // 创建助手消息元素
            const assistantMessageElement = this.addMessage('assistant', '', true);
            let fullResponse = '';

            // 处理流式响应
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let currentEvent = '';

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    
                    if (done) {
                        // 流结束，使用统一的处理方法
                        this.handleStreamComplete(assistantMessageElement, fullResponse);
                        break;
                    }

                    // 解码数据并添加到缓冲区
                    buffer += decoder.decode(value, { stream: true });
                    
                    // 按行分割处理
                    const lines = buffer.split('\n');
                    // 保留最后一行（可能不完整）
                    buffer = lines.pop() || '';
                    
                    for (const line of lines) {
                        if (line.trim() === '') continue;
                        
                        console.log('[SSE调试] 收到行:', line);
                        
                        // 解析SSE格式
                        if (line.startsWith('id:')) {
                            console.log('[SSE调试] 解析到ID');
                            continue;
                        } else if (line.startsWith('event:')) {
                            // 兼容 "event:message" 和 "event: message" 两种格式
                            currentEvent = line.substring(6).trim();
                            console.log('[SSE调试] 解析到事件类型:', currentEvent);
                            // 注意：后端统一使用 "message" 事件名，真正的类型在 data 的 JSON 中
                            continue;
                        } else if (line.startsWith('data:')) {
                            // 兼容 "data:xxx" 和 "data: xxx" 两种格式
                            const rawData = line.substring(5).trim();
                            console.log('[SSE调试] 解析到数据, currentEvent:', currentEvent, ', rawData:', rawData);
                            
                            // 兼容旧格式 [DONE] 标记
                            if (rawData === '[DONE]') {
                                // 流结束标记，将内容转换为Markdown渲染
                                this.handleStreamComplete(assistantMessageElement, fullResponse);
                                return;
                            }
                            
                            // 处理 SSE 数据
                            try {
                                // 尝试解析为 SseMessage 格式的 JSON
                                const sseMessage = JSON.parse(rawData);
                                console.log('[SSE调试] 解析JSON成功:', sseMessage);
                                
                                if (sseMessage && typeof sseMessage.type === 'string') {
                                    if (sseMessage.type === 'content') {
                                        const content = sseMessage.data || '';
                                        fullResponse += content;
                                        console.log('[SSE调试] 添加内容:', content);
                                        
                                        // 实时渲染 Markdown
                                        if (assistantMessageElement) {
                                            const messageContent = assistantMessageElement.querySelector('.message-content');
                                            messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                            // 高亮代码块
                                            this.highlightCodeBlocks(messageContent);
                                            this.scrollToBottom();
                                        }
                                    } else if (sseMessage.type === 'done') {
                                        console.log('[SSE调试] 收到done标记，流结束');
                                        this.handleStreamComplete(assistantMessageElement, fullResponse);
                                        return;
                                    } else if (sseMessage.type === 'error') {
                                        console.error('[SSE调试] 收到错误:', sseMessage.data);
                                        if (assistantMessageElement) {
                                            const messageContent = assistantMessageElement.querySelector('.message-content');
                                            messageContent.innerHTML = this.renderMarkdown('错误: ' + (sseMessage.data || '未知错误'));
                                        }
                                        return;
                                    }
                                } else {
                                    // 不是标准 SseMessage 格式，尝试兼容处理
                                    console.log('[SSE调试] 非标准格式，尝试兼容处理');
                                    fullResponse += rawData;
                                    if (assistantMessageElement) {
                                        const messageContent = assistantMessageElement.querySelector('.message-content');
                                        messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                        this.highlightCodeBlocks(messageContent);
                                        this.scrollToBottom();
                                    }
                                }
                            } catch (e) {
                                // JSON 解析失败，尝试兼容旧格式
                                console.log('[SSE调试] JSON解析失败，使用兼容模式:', e.message);
                                if (rawData === '') {
                                    fullResponse += '\n';
                                } else {
                                    fullResponse += rawData;
                                }
                                
                                if (assistantMessageElement) {
                                    const messageContent = assistantMessageElement.querySelector('.message-content');
                                    messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                    this.highlightCodeBlocks(messageContent);
                                    this.scrollToBottom();
                                }
                            }
                        }
                    }
                }
            } finally {
                reader.releaseLock();
            }
        } catch (error) {
            throw error;
        }
    }

    // 添加消息到聊天界面
    addMessage(type, content, isStreaming = false, saveToHistory = true) {
        // 检查是否是第一条消息，如果是则移除居中样式
        const isFirstMessage = this.chatMessages && this.chatMessages.querySelectorAll('.message').length === 0;
        
        // 保存消息到当前对话历史（如果不是流式消息且需要保存）
        if (!isStreaming && saveToHistory && content) {
            this.currentChatHistory.push({
                type: type,
                content: content,
                timestamp: new Date().toISOString()
            });
        }
        
        const messageDiv = document.createElement('div');
        messageDiv.className = `message ${type}${isStreaming ? ' streaming' : ''}`;

        // 如果是assistant消息，添加头像图标
        if (type === 'assistant') {
            const messageAvatar = document.createElement('div');
            messageAvatar.className = 'message-avatar';
            messageAvatar.innerHTML = `
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/>
                </svg>
            `;
            messageDiv.appendChild(messageAvatar);
        }

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        const messageContent = document.createElement('div');
        messageContent.className = 'message-content';
        
        // 如果是assistant消息且不是流式消息，使用Markdown渲染
        if (type === 'assistant' && !isStreaming) {
            messageContent.innerHTML = this.renderMarkdown(content);
            // 高亮代码块
            this.highlightCodeBlocks(messageContent);
        } else {
            // 用户消息或流式消息使用纯文本
            messageContent.textContent = content;
        }

        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);

        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            
            // 如果是第一条消息，移除居中样式并添加动画
            if (isFirstMessage && this.chatContainer) {
                this.chatContainer.classList.remove('centered');
                // 添加动画类
                this.chatContainer.style.transition = 'all 0.5s ease';
            }
            
            this.scrollToBottom();
        }

        return messageDiv;
    }

    // 添加带加载动画的消息
    addLoadingMessage(content) {
        const messageDiv = document.createElement('div');
        messageDiv.className = 'message assistant';

        // 添加头像图标
        const messageAvatar = document.createElement('div');
        messageAvatar.className = 'message-avatar';
        messageAvatar.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/>
            </svg>
        `;
        messageDiv.appendChild(messageAvatar);

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        const messageContent = document.createElement('div');
        messageContent.className = 'message-content loading-message-content';
        
        // 创建文本和动画容器
        const textSpan = document.createElement('span');
        textSpan.textContent = content;
        
        // 创建旋转动画图标
        const loadingIcon = document.createElement('span');
        loadingIcon.className = 'loading-spinner-icon';
        loadingIcon.innerHTML = `
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" fill="currentColor" opacity="0.2"/>
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10c1.54 0 3-.36 4.28-1l-1.5-2.6C13.64 19.62 12.84 20 12 20c-4.41 0-8-3.59-8-8s3.59-8 8-8c.84 0 1.64.38 2.18 1l1.5-2.6C13 2.36 12.54 2 12 2z" fill="currentColor"/>
            </svg>
        `;
        
        messageContent.appendChild(textSpan);
        messageContent.appendChild(loadingIcon);
        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);

        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            
            // 如果是第一条消息，移除居中样式
            const isFirstMessage = this.chatMessages.querySelectorAll('.message').length === 1;
            if (isFirstMessage && this.chatContainer) {
                this.chatContainer.classList.remove('centered');
                this.chatContainer.style.transition = 'all 0.5s ease';
            }
            
            this.scrollToBottom();
        }

        return messageDiv;
    }
    
    // 检查并设置居中样式
    checkAndSetCentered() {
        if (this.chatMessages && this.chatContainer) {
            const hasMessages = this.chatMessages.querySelectorAll('.message').length > 0;
            if (!hasMessages) {
                this.chatContainer.classList.add('centered');
            } else {
                this.chatContainer.classList.remove('centered');
            }
        }
    }

    // 滚动到底部
    scrollToBottom() {
        if (this.chatMessages) {
            this.chatMessages.scrollTop = this.chatMessages.scrollHeight;
        }
    }

    // 处理流式传输完成
    handleStreamComplete(assistantMessageElement, fullResponse) {
        if (assistantMessageElement) {
            assistantMessageElement.classList.remove('streaming');
            const messageContent = assistantMessageElement.querySelector('.message-content');
            if (messageContent) {
                messageContent.innerHTML = this.renderMarkdown(fullResponse);
                // 高亮代码块
                this.highlightCodeBlocks(messageContent);
            }
        }
        // 保存流式消息到历史记录
        if (fullResponse) {
            this.currentChatHistory.push({
                type: 'assistant',
                content: fullResponse,
                timestamp: new Date().toISOString()
            });
            // 如果当前对话是从历史记录加载的，更新历史记录
            if (this.isCurrentChatFromHistory) {
                this.updateCurrentChatHistory();
                this.renderChatHistory();
            }
        }
    }

    // 显示通知
    showNotification(message, type = 'info') {
        // 创建通知元素
        const notification = document.createElement('div');
        notification.className = `notification ${type}`;
        notification.textContent = message;
        notification.style.cssText = `
            position: fixed;
            top: 20px;
            right: 20px;
            padding: 15px 20px;
            border-radius: 8px;
            color: white;
            font-weight: 500;
            z-index: 10000;
            animation: slideIn 0.3s ease;
            max-width: 300px;
        `;

        // 根据类型设置颜色（Google Material Design配色）
        const colors = {
            info: '#1a73e8',
            success: '#34a853',
            warning: '#fbbc04',
            error: '#ea4335'
        };
        notification.style.backgroundColor = colors[type] || colors.info;

        // 添加到页面
        document.body.appendChild(notification);

        // 3秒后自动移除
        setTimeout(() => {
            notification.style.animation = 'slideOut 0.3s ease';
            setTimeout(() => {
                if (notification.parentNode) {
                    notification.parentNode.removeChild(notification);
                }
            }, 300);
        }, 3000);
    }

    // 处理文件选择
    handleFileSelect(event) {
        const file = event.target.files[0];
        if (file) {
            // 验证文件格式
            if (!this.validateFileType(file)) {
                this.showNotification('只支持 txt、md、word、pdf、图片 文件', 'error');
                this.fileInput.value = '';
                return;
            }
            this.uploadFile(file);
        }
    }

    // 验证文件类型
    validateFileType(file) {
        const fileName = file.name.toLowerCase();
        const allowedExtensions = ['.txt', '.md', '.markdown', '.doc', '.docx', '.pdf', '.png', '.jpg', '.jpeg', '.bmp', '.gif'];
        return allowedExtensions.some(ext => fileName.endsWith(ext));
    }

    // 上传文件到知识库
    async uploadFile(file) {
        // 再次验证文件类型（双重保险）
        if (!this.validateFileType(file)) {
            this.showNotification('只支持 txt、md、word、pdf、图片 文件', 'error');
            return;
        }

        // 验证文件大小（限制为50MB）
        const maxSize = 50 * 1024 * 1024;
        if (file.size > maxSize) {
            this.showNotification('文件大小不能超过50MB', 'error');
            return;
        }

        // 锁定前端并显示上传遮罩层
        this.isStreaming = true;
        this.updateUI();
        this.showUploadOverlay(true, file.name);

        try {
            // 创建 FormData
            const formData = new FormData();
            formData.append('file', file);

            // 发送上传请求
            const response = await fetch(`${this.apiBaseUrl}/upload`, {
                method: 'POST',
                body: formData
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            const data = await response.json();

            if ((data.code === 200 || data.message === 'success') && data.data) {
                const uploadData = data.data || {};
                const noticeType = uploadData.indexed === false ? 'warning' : 'success';
                const noticeText = uploadData.indexed === false
                    ? `${file.name} 已保存，但索引失败：${uploadData.indexMessage || '未知错误'}`
                    : `${file.name} 上传到知识库成功`;
                this.showNotification(noticeText, noticeType);
                if (!this.isKnowledgePageVisible()) {
                    const successMessage = noticeText;
                    this.addMessage('assistant', successMessage, false, true);
                }
                await this.loadKnowledgeFiles();
            } else {
                throw new Error(data.message || '上传失败');
            }
        } catch (error) {
            console.error('文件上传失败:', error);
            this.showNotification('文件上传失败: ' + error.message, 'error');
        } finally {
            // 清空文件输入
            if (this.fileInput) {
                this.fileInput.value = '';
            }
            // 解锁前端
            this.isStreaming = false;
            this.showUploadOverlay(false);
            this.updateUI();
        }
    }

    // 加载知识库文件列表
    async loadKnowledgeFiles() {
        if (!this.knowledgeFilesList) {
            return;
        }

        this.knowledgeFilesList.querySelectorAll('.knowledge-file-item, .knowledge-empty').forEach(el => el.remove());

        try {
            const response = await fetch(`${this.apiBaseUrl}/knowledge/files`);
            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            const data = await response.json();
            if (data.code !== 200) {
                throw new Error(data.message || '加载知识库文件失败');
            }

            this.knowledgeFiles = data.data || [];
            this.renderKnowledgeFiles(this.knowledgeFiles);
            this.updateKnowledgeStats(this.knowledgeFiles);
        } catch (error) {
            console.error('加载知识库文件失败:', error);
            this.knowledgeFilesList.querySelectorAll('.knowledge-file-item, .knowledge-empty').forEach(el => el.remove());
            const errDiv = document.createElement('div');
            errDiv.className = 'knowledge-empty';
            errDiv.innerHTML = '<p>文件列表加载失败</p>';
            this.knowledgeFilesList.appendChild(errDiv);
        }
    }

    // 渲染知识库文件列表
    renderKnowledgeFiles(files) {
        if (!this.knowledgeFilesList) {
            return;
        }

        // Clear all file items (keep the header)
        const header = this.knowledgeFilesList.querySelector('.knowledge-file-list-header');
        const existingItems = this.knowledgeFilesList.querySelectorAll('.knowledge-file-item');
        existingItems.forEach(item => item.remove());

        const keyword = (this.knowledgeSearchInput?.value || '').trim().toLowerCase();
        const visibleFiles = keyword
            ? files.filter(file => (file.name || '').toLowerCase().includes(keyword))
            : files;

        if (!visibleFiles || visibleFiles.length === 0) {
            const emptyDiv = document.createElement('div');
            emptyDiv.className = 'knowledge-empty';
            emptyDiv.innerHTML = '<p>暂无文件</p>';
            this.knowledgeFilesList.appendChild(emptyDiv);
            return;
        }

        visibleFiles.forEach(file => {
            const item = document.createElement('div');
            item.className = 'knowledge-file-item';
            item.dataset.fileName = file.name || '';

            const ext = (file.extension || '').toUpperCase();
            const isUrlSource = file.sourceType === 'url';
            const indexText = file.indexable ? '已入库/可检索' : '仅保存';
            const lastModified = file.lastModified ? this.formatDateTime(file.lastModified) : '-';

            const iconClass = isUrlSource ? 'file-icon-default' : ext === 'PDF' ? 'file-icon-pdf' : ext === 'DOCX' || ext === 'DOC' ? 'file-icon-docx' : ['PNG','JPG','JPEG','BMP','GIF'].includes(ext) ? 'file-icon-image' : ext === 'TXT' || ext === 'MD' ? 'file-icon-txt' : 'file-icon-default';
            const typeText = isUrlSource ? 'URL' : (ext || 'FILE');
            const sizeText = isUrlSource && file.chunkCount ? `${file.chunkCount} chunks` : this.formatFileSize(file.size || 0);
            const actionHtml = isUrlSource
                ? '<button class="table-action-btn" disabled>已索引</button><button class="table-action-btn danger" disabled>删除</button>'
                : `<button class="table-action-btn" data-action="reindex" data-file="${this.escapeHtml(file.name || '')}" ${file.indexable ? '' : 'disabled'}>重建索引</button>
                    <button class="table-action-btn danger" data-action="delete" data-file="${this.escapeHtml(file.name || '')}">删除</button>`;

            item.innerHTML = `
                <div class="kf-col-name">
                    <span class="file-icon ${iconClass}">${this.escapeHtml(isUrlSource ? 'URL' : (ext.slice(0, 3) || 'FILE'))}</span>
                    <span class="file-name-text">${this.escapeHtml(file.name || '')}</span>
                </div>
                <div class="kf-col-type">${this.escapeHtml(typeText)}</div>
                <div class="kf-col-size">${this.escapeHtml(sizeText)}</div>
                <div class="kf-col-time">${lastModified}</div>
                <div class="kf-col-action">
                    ${actionHtml}
                </div>
            `;

            if (!isUrlSource) {
                item.querySelector('[data-action="reindex"]').addEventListener('click', () => this.reindexKnowledgeFile(file.name));
                item.querySelector('[data-action="delete"]').addEventListener('click', () => this.deleteKnowledgeFile(file.name));
            }
            this.knowledgeFilesList.appendChild(item);
        });
    }

    updateKnowledgeStats(files) {
        const totalCount = files.length;
        const indexableCount = files.filter(file => file.indexable).length;
        const totalSize = files.reduce((sum, file) => sum + (file.size || 0), 0);

        if (this.knowledgeTotalCount) this.knowledgeTotalCount.textContent = totalCount;
        if (this.knowledgeIndexableCount) this.knowledgeIndexableCount.textContent = indexableCount;
        if (this.knowledgeTotalSize) this.knowledgeTotalSize.textContent = this.formatFileSize(totalSize);
    }

    async reindexKnowledgeFile(fileName) {
        if (!fileName) return;

        try {
            const response = await fetch(`${this.apiBaseUrl}/knowledge/files/${encodeURIComponent(fileName)}/reindex`, {
                method: 'POST'
            });
            const data = await response.json();
            if (!response.ok || data.code !== 200) {
                throw new Error(data.message || '重建索引失败');
            }
            this.showNotification(`${fileName} 重建索引完成`, 'success');
            await this.loadKnowledgeFiles();
        } catch (error) {
            console.error('重建索引失败:', error);
            this.showNotification('重建索引失败: ' + error.message, 'error');
        }
    }

    async deleteKnowledgeFile(fileName) {
        if (!fileName) return;
        if (!confirm(`确认删除 ${fileName} 吗？`)) {
            return;
        }

        try {
            const response = await fetch(`${this.apiBaseUrl}/knowledge/files/${encodeURIComponent(fileName)}`, {
                method: 'DELETE'
            });
            const data = await response.json();
            if (!response.ok || data.code !== 200) {
                throw new Error(data.message || '删除失败');
            }
            this.showNotification(`${fileName} 已删除`, 'success');
            await this.loadKnowledgeFiles();
        } catch (error) {
            console.error('删除文件失败:', error);
            this.showNotification('删除失败: ' + error.message, 'error');
        }
    }

    // 格式化文件大小
    formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
    }

    formatDateTime(value) {
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) {
            return '-';
        }
        return date.toLocaleString('zh-CN', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit'
        });
    }

    // 发送智能运维请求（SSE 流式模式）
    async sendAIOpsRequest(loadingMessageElement) {
        try {
            const response = await fetch(`${this.apiBaseUrl}/ai_ops`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                }
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            let fullResponse = '';

            // 处理 SSE 流式响应
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let currentEvent = 'message'; // 默认事件类型为 message

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    
                    if (done) {
                        // 流结束，更新最终内容
                        if (fullResponse) {
                            console.log('AI Ops 流结束，更新最终内容，长度:', fullResponse.length);
                            this.updateAIOpsMessage(loadingMessageElement, fullResponse, []);
                        }
                        break;
                    }

                    // 解码数据并添加到缓冲区
                    buffer += decoder.decode(value, { stream: true });
                    
                    // 按行分割处理
                    const lines = buffer.split('\n');
                    // 保留最后一行（可能不完整）
                    buffer = lines.pop() || '';
                    
                    for (const line of lines) {
                        if (line.trim() === '') continue;
                        
                        console.log('[AI Ops SSE] 收到行:', line);
                        
                        // 解析 SSE 格式
                        if (line.startsWith('id:')) {
                            continue;
                        } else if (line.startsWith('event:')) {
                            currentEvent = line.substring(6).trim();
                            console.log('[AI Ops SSE] 事件类型:', currentEvent);
                            continue;
                        } else if (line.startsWith('data:')) {
                            const rawData = line.substring(5).trim();
                            console.log('[AI Ops SSE] 数据:', rawData, ', currentEvent:', currentEvent);
                            
                            // 解析可能包含多个JSON对象的数据
                            const processJsonMessages = (data) => {
                                const jsonPattern = /\{"type"\s*:\s*"[^"]+"\s*,\s*"data"\s*:\s*(?:"[^"]*"|null)\}/g;
                                const matches = data.match(jsonPattern);
                                
                                if (matches && matches.length > 0) {
                                    console.log('[AI Ops SSE] 匹配到', matches.length, '个JSON对象');
                                    for (const jsonStr of matches) {
                                        try {
                                            const sseMessage = JSON.parse(jsonStr);
                                            if (sseMessage.type === 'content') {
                                                fullResponse += sseMessage.data || '';
                                            } else if (sseMessage.type === 'done') {
                                                console.log('AI Ops 流完成，最终内容长度:', fullResponse.length);
                                                this.updateAIOpsMessage(loadingMessageElement, fullResponse, []);
                                                return true;
                                            } else if (sseMessage.type === 'error') {
                                                throw new Error(sseMessage.data || '智能运维分析失败');
                                            }
                                        } catch (e) {
                                            if (e.message.includes('智能运维')) throw e;
                                            console.log('[AI Ops SSE] 单个JSON解析失败:', jsonStr);
                                        }
                                    }
                                    if (loadingMessageElement) {
                                        this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                                    }
                                    return false;
                                }
                                return null;
                            };
                            
                            const result = processJsonMessages(rawData);
                            if (result === true) {
                                return; // 流结束
                            } else if (result === null) {
                                // 没有匹配到多个JSON，尝试单个JSON解析
                                try {
                                    const sseMessage = JSON.parse(rawData);
                                    if (sseMessage && sseMessage.type) {
                                        if (sseMessage.type === 'content') {
                                            fullResponse += sseMessage.data || '';
                                            if (loadingMessageElement) {
                                                this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                                            }
                                        } else if (sseMessage.type === 'done') {
                                            console.log('AI Ops 流完成，最终内容长度:', fullResponse.length);
                                            this.updateAIOpsMessage(loadingMessageElement, fullResponse, []);
                                            return;
                                        } else if (sseMessage.type === 'error') {
                                            throw new Error(sseMessage.data || '智能运维分析失败');
                                        }
                                    } else {
                                        fullResponse += rawData;
                                        if (loadingMessageElement) {
                                            this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                                        }
                                    }
                                } catch (e) {
                                    if (e.message.includes('智能运维')) throw e;
                                    // 非 JSON 格式，直接追加原始数据
                                    fullResponse += rawData;
                                    if (loadingMessageElement) {
                                        this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                reader.releaseLock();
            }
        } catch (error) {
            throw error;
        }
    }

    // 更新智能运维流式内容（实时显示）
    updateAIOpsStreamContent(messageElement, content) {
        if (!messageElement) return;
        
        // 添加 aiops-message 类
        messageElement.classList.add('aiops-message');
        
        const messageContentWrapper = messageElement.querySelector('.message-content-wrapper');
        if (messageContentWrapper) {
            let messageContent = messageContentWrapper.querySelector('.message-content');
            if (!messageContent) {
                messageContent = document.createElement('div');
                messageContent.className = 'message-content';
                messageContentWrapper.appendChild(messageContent);
            }
            // 流式显示时使用纯文本
            messageContent.textContent = content;
            this.scrollToBottom();
        }
    }

    // 更新智能运维消息（带折叠详情）
    updateAIOpsMessage(messageElement, response, details) {
        console.log('updateAIOpsMessage 被调用');
        console.log('messageElement:', messageElement);
        console.log('response:', response);
        console.log('response length:', response ? response.length : 0);
        console.log('details:', details);
        
        if (!messageElement) {
            // 如果没有传入消息元素，则创建新消息
            console.log('messageElement 为空，创建新消息');
            return this.addAIOpsMessage(response, details);
        }

        // 添加aiops-message类
        messageElement.classList.add('aiops-message');

        // 获取消息内容包装器
        const messageContentWrapper = messageElement.querySelector('.message-content-wrapper');
        if (!messageContentWrapper) {
            console.error('未找到 message-content-wrapper');
            return;
        }

        // 清空现有内容（保留消息内容容器）
        const messageContent = messageContentWrapper.querySelector('.message-content');
        if (!messageContent) {
            console.error('未找到 message-content');
            return;
        }

        // 移除加载动画相关的类和内容
        messageContent.classList.remove('loading-message-content');
        messageContent.textContent = '';
        
        // 移除加载图标（如果存在）
        const loadingIcon = messageContent.querySelector('.loading-spinner-icon');
        if (loadingIcon) {
            loadingIcon.remove();
        }

        // 详情部分（可折叠）- 先显示
        if (details && details.length > 0) {
            // 检查是否已存在详情容器
            let detailsContainer = messageElement.querySelector('.aiops-details');
            if (!detailsContainer) {
                detailsContainer = document.createElement('div');
                detailsContainer.className = 'aiops-details';
                messageContentWrapper.insertBefore(detailsContainer, messageContent);
            } else {
                // 清空现有详情
                detailsContainer.innerHTML = '';
            }

            const detailsToggle = document.createElement('div');
            detailsToggle.className = 'details-toggle';
            detailsToggle.innerHTML = `
                <svg class="toggle-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M9 18L15 12L9 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span>查看详细步骤 (${details.length}条)</span>
            `;

            const detailsContent = document.createElement('div');
            detailsContent.className = 'details-content';
            
            details.forEach((detail, index) => {
                const detailItem = document.createElement('div');
                detailItem.className = 'detail-item';
                detailItem.innerHTML = `<strong>步骤 ${index + 1}:</strong> ${this.escapeHtml(detail)}`;
                detailsContent.appendChild(detailItem);
            });

            // 点击切换折叠状态
            detailsToggle.addEventListener('click', () => {
                detailsContent.classList.toggle('expanded');
                detailsToggle.classList.toggle('expanded');
            });

            detailsContainer.appendChild(detailsToggle);
            detailsContainer.appendChild(detailsContent);
        }

        // 更新主要响应内容（使用Markdown渲染）
        console.log('开始渲染 Markdown');
        const renderedHtml = this.renderMarkdown(response);
        console.log('Markdown 渲染完成，HTML 长度:', renderedHtml ? renderedHtml.length : 0);
        messageContent.innerHTML = renderedHtml;
        console.log('innerHTML 已设置');
        // 高亮代码块
        this.highlightCodeBlocks(messageContent);
        console.log('代码块高亮完成');
        
        // 保存到历史记录
        this.currentChatHistory.push({
            type: 'assistant',
            content: response,
            timestamp: new Date().toISOString()
        });
        
        this.scrollToBottom();
        return messageElement;
    }

    // 添加智能运维消息（带折叠详情）- 保留用于兼容性
    addAIOpsMessage(response, details) {
        const messageDiv = document.createElement('div');
        messageDiv.className = 'message assistant aiops-message';

        // 添加头像图标
        const messageAvatar = document.createElement('div');
        messageAvatar.className = 'message-avatar';
        messageAvatar.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/>
            </svg>
        `;
        messageDiv.appendChild(messageAvatar);

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        // 详情部分（可折叠）- 先显示
        if (details && details.length > 0) {
            const detailsContainer = document.createElement('div');
            detailsContainer.className = 'aiops-details';

            const detailsToggle = document.createElement('div');
            detailsToggle.className = 'details-toggle';
            detailsToggle.innerHTML = `
                <svg class="toggle-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M9 18L15 12L9 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span>查看详细步骤 (${details.length}条)</span>
            `;

            const detailsContent = document.createElement('div');
            detailsContent.className = 'details-content';
            
            details.forEach((detail, index) => {
                const detailItem = document.createElement('div');
                detailItem.className = 'detail-item';
                detailItem.innerHTML = `<strong>步骤 ${index + 1}:</strong> ${this.escapeHtml(detail)}`;
                detailsContent.appendChild(detailItem);
            });

            // 点击切换折叠状态
            detailsToggle.addEventListener('click', () => {
                detailsContent.classList.toggle('expanded');
                detailsToggle.classList.toggle('expanded');
            });

            detailsContainer.appendChild(detailsToggle);
            detailsContainer.appendChild(detailsContent);
            messageContentWrapper.appendChild(detailsContainer);
        }

        // 主要响应内容 - 后显示（使用Markdown渲染）
        const messageContent = document.createElement('div');
        messageContent.className = 'message-content';
        messageContent.innerHTML = this.renderMarkdown(response);
        // 高亮代码块
        this.highlightCodeBlocks(messageContent);
        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);
        
        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            this.scrollToBottom();
        }

        return messageDiv;
    }

    // HTML转义
    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // 触发智能运维（点击智能运维按钮时直接调用）
    async triggerAIOps() {
        if (this.isStreaming) {
            this.showNotification('请等待当前操作完成', 'warning');
            return;
        }

        this.showChatPage();
        // 新建对话
        this.newChat();
        
        // 添加"分析中..."的消息（带旋转动画）
        const loadingMessage = this.addLoadingMessage('分析中...');
        this.currentAIOpsMessage = loadingMessage; // 保存消息引用用于后续更新
        
        // 设置发送状态
        this.isStreaming = true;
        this.updateUI();

        try {
            await this.sendAIOpsRequest(loadingMessage);
        } catch (error) {
            console.error('智能运维分析失败:', error);
            // 更新消息为错误信息
            if (loadingMessage) {
                const messageContent = loadingMessage.querySelector('.message-content');
                if (messageContent) {
                    messageContent.textContent = '抱歉，智能运维分析时出现错误：' + error.message;
                }
            }
        } finally {
            this.isStreaming = false;
            this.currentAIOpsMessage = null;
            this.updateUI();
        }
    }

    // 显示/隐藏加载遮罩层
    showLoadingOverlay(show) {
        if (this.loadingOverlay) {
            if (show) {
                this.loadingOverlay.style.display = 'flex';
                // 更新文字为智能运维
                const loadingText = this.loadingOverlay.querySelector('.loading-text');
                const loadingSubtext = this.loadingOverlay.querySelector('.loading-subtext');
                if (loadingText) loadingText.textContent = '智能运维分析中，请稍候...';
                if (loadingSubtext) loadingSubtext.textContent = '后端正在处理，请耐心等待';
                // 防止页面滚动
                document.body.style.overflow = 'hidden';
            } else {
                this.loadingOverlay.style.display = 'none';
                // 恢复页面滚动
                document.body.style.overflow = '';
            }
        }
    }

    // 显示/隐藏上传遮罩层
    showUploadOverlay(show, fileName = '') {
        if (this.loadingOverlay) {
            if (show) {
                this.loadingOverlay.style.display = 'flex';
                const loadingText = this.loadingOverlay.querySelector('.loading-text');
                const loadingSubtext = this.loadingOverlay.querySelector('.loading-subtext');
                if (loadingText) loadingText.textContent = '正在上传文件...';
                if (loadingSubtext) loadingSubtext.textContent = fileName ? `上传: ${fileName}` : '请稍候';
                document.body.style.overflow = 'hidden';
            } else {
                this.loadingOverlay.style.display = 'none';
                document.body.style.overflow = '';
            }
        }
    }

    // ========== 知识库 URL 导入 ==========

    async ingestUrl() {
        const url = this.knowledgeUrlInput?.value.trim();
        if (!url) {
            this.showNotification('请输入网页URL', 'warning');
            return;
        }
        try {
            this.showNotification('正在抓取网页...', 'info');
            const title = this.knowledgeUrlTitle?.value.trim() || '';
            const response = await fetch(`${this.apiBaseUrl}/knowledge/url`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ url, title })
            });
            if (!response.ok) throw new Error(await response.text());
            const data = await response.json();
            if (data.code === 200) {
                this.showNotification(`成功索引 ${data.data.chunk_count} 个文本块`, 'success');
                if (this.knowledgeUrlInput) this.knowledgeUrlInput.value = '';
                if (this.knowledgeUrlTitle) this.knowledgeUrlTitle.value = '';
                this.loadKnowledgeFiles();
            } else {
                throw new Error(data.message || '抓取失败');
            }
        } catch (e) {
            this.showNotification('网页抓取失败: ' + e.message, 'error');
        }
    }

    handleKnowledgeFileUpload(event) {
        const file = event.target.files[0];
        if (file) this.uploadKnowledgeFile(file);
    }

    async uploadKnowledgeFile(file) {
        const formData = new FormData();
        formData.append('file', file);
        try {
            this.showNotification('正在上传...', 'info');
            const response = await fetch(`${this.apiBaseUrl}/knowledge/files`, {
                method: 'POST', body: formData
            });
            const data = await response.json();
            if (data.code === 200) {
                const uploadData = data.data || {};
                const fileName = uploadData.fileName || file.name;
                if (uploadData.indexed === false) {
                    this.showNotification(`文件 "${fileName}" 已保存，但索引失败：${uploadData.indexMessage || '未知错误'}`, 'warning');
                } else {
                    this.showNotification(`文件 "${fileName}" 上传并索引成功`, 'success');
                }
                this.loadKnowledgeFiles();
            } else {
                throw new Error(data.message || '上传失败');
            }
        } catch (e) {
            this.showNotification('上传失败: ' + e.message, 'error');
        }
    }

    // ========== 技能管理 ==========

    async loadSkills() {
        if (!this.skillsGrid) return;
        try {
            const category = this.skillCategoryFilter?.value || '';
            const query = category ? `?category=${encodeURIComponent(category)}` : '';
            const response = await fetch(`${this.apiBaseUrl}/skills${query}`);
            const data = await response.json();
            const skills = data.data?.skills || [];
            this.renderSkillCards(skills);
        } catch (e) {
            console.error('加载技能列表失败:', e);
            this.skillsGrid.innerHTML = '<div class="skills-empty"><p>加载失败，请稍后重试</p></div>';
        }
    }

    renderSkillCards(skills) {
        if (!this.skillsGrid) return;
        if (!skills || skills.length === 0) {
            this.skillsGrid.innerHTML = '<div class="skills-empty"><div class="skills-empty-icon">🔧</div><p>暂无技能</p><span>可以通过"从GitHub搜索"或"手动创建"添加技能</span></div>';
            return;
        }

        const categoryLabels = { troubleshooting: '故障排查', monitoring: '监控', deployment: '部署', networking: '网络', security: '安全', database: '数据库', general: '通用' };
        const sourceLabels = { precipitated: 'AI沉淀', searched: 'GitHub', manual: '手动创建' };

        this.skillsGrid.innerHTML = skills.map(s => `
            <div class="skill-card" data-skill-id="${this.escapeHtml(s.id)}">
                <div class="skill-card-header">
                    <span class="skill-card-name">${this.escapeHtml(s.name)}</span>
                    <label class="skill-card-toggle" onclick="event.stopPropagation();">
                        <input type="checkbox" ${s.enabled ? 'checked' : ''} onchange="window._app.toggleSkill('${this.escapeHtml(s.id)}', this.checked)">
                        <span class="skill-toggle-slider"></span>
                    </label>
                </div>
                <div class="skill-card-description">${this.escapeHtml(s.description || '暂无描述')}</div>
                <div class="skill-card-badges">
                    <span class="skill-badge skill-badge-category">${categoryLabels[s.category] || s.category}</span>
                    <span class="skill-badge skill-badge-source-${s.source_type}">${sourceLabels[s.source_type] || s.source_type}</span>
                    ${(s.tags || []).map(t => `<span class="skill-badge" style="background:#f1f3f4;color:#5f6368;">${this.escapeHtml(t)}</span>`).join('')}
                </div>
                <div class="skill-card-footer">
                    <div class="skill-card-stats">
                        <span class="skill-card-stat">📊 ${s.usage_count || 0}次</span>
                        <span class="skill-card-stat">✅ ${Math.round((s.success_rate || 0) * 100)}%</span>
                    </div>
                    <div class="skill-card-actions">
                        <button class="skill-action-btn" title="查看详情" onclick="window._app.openSkillDetail('${this.escapeHtml(s.id)}')">📋</button>
                        <button class="skill-action-btn delete" title="删除" onclick="event.stopPropagation(); window._app.deleteSkill('${this.escapeHtml(s.id)}')">🗑</button>
                    </div>
                </div>
            </div>
        `).join('');

        this.skillsGrid.querySelectorAll('.skill-card').forEach(card => {
            card.addEventListener('click', (e) => {
                if (e.target.closest('.skill-card-toggle') || e.target.closest('.skill-action-btn')) return;
                this.openSkillDetail(card.dataset.skillId);
            });
        });
    }

    searchSkills() {
        const query = this.skillSearchInput?.value.trim();
        if (!query) { this.loadSkills(); return; }
        fetch(`${this.apiBaseUrl}/skills/search`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query, topK: 10 })
        })
        .then(r => r.json())
        .then(data => { this.renderSkillCards(data.data?.skills || []); })
        .catch(e => { console.error('技能搜索失败:', e); });
    }

    openGithubSearchModal() {
        if (this.githubSearchModal) { this.githubSearchModal.style.display = 'flex'; if (this.githubSearchInput) this.githubSearchInput.value = ''; if (this.githubSearchResults) this.githubSearchResults.innerHTML = ''; }
    }
    closeGithubSearchModal() { if (this.githubSearchModal) this.githubSearchModal.style.display = 'none'; }

    async searchGithubSkills() {
        const query = this.githubSearchInput?.value.trim();
        if (!query) { this.showNotification('请输入搜索关键词', 'warning'); return; }
        if (this.githubSearchResults) this.githubSearchResults.innerHTML = '<p style="text-align:center;color:#5f6368;padding:20px;">搜索中...</p>';
        try {
            const response = await fetch(`${this.apiBaseUrl}/skills/search-github`, {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ query, topK: 10 })
            });
            const data = await response.json();
            const repos = data.data?.repos || [];
            this.renderGithubSearchResults(repos);
        } catch (e) {
            if (this.githubSearchResults) this.githubSearchResults.innerHTML = '<p style="text-align:center;color:#ea4335;padding:20px;">搜索失败</p>';
        }
    }

    renderGithubSearchResults(repos) {
        if (!this.githubSearchResults) return;
        if (!repos.length) { this.githubSearchResults.innerHTML = '<p style="text-align:center;color:#9aa0a6;padding:20px;">未找到相关 MCP Server</p>'; return; }
        this.githubSearchResults.innerHTML = repos.map(r => `
            <div class="github-repo-item">
                <div class="github-repo-name"><a href="${this.escapeHtml(r.html_url)}" target="_blank" rel="noopener">${this.escapeHtml(r.full_name)}</a></div>
                <div class="github-repo-desc">${this.escapeHtml(r.description || '暂无描述')}</div>
                <div class="github-repo-meta">
                    <span class="github-repo-stars">⭐ ${r.stargazers_count}</span>
                    <span>${this.escapeHtml(r.language || 'N/A')}</span>
                </div>
                <button class="github-repo-install-btn" onclick="window._app.installGithubSkill(${JSON.stringify(JSON.stringify(r))})">安装技能</button>
            </div>
        `).join('');
    }

    async installGithubSkill(repo) {
        if (typeof repo === 'string') { try { repo = JSON.parse(repo); } catch (e) {} }
        try {
            const response = await fetch(`${this.apiBaseUrl}/skills/install-github`, {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(repo)
            });
            const data = await response.json();
            if (data.code === 200) {
                this.showNotification(`技能 "${data.data.name}" 安装成功`, 'success');
                this.closeGithubSearchModal();
                this.loadSkills();
            }
        } catch (e) {
            this.showNotification('安装失败: ' + e.message, 'error');
        }
    }

    openCreateSkillModal() {
        if (this.skillCreateModal) {
            this.skillCreateModal.style.display = 'flex';
            ['skillFormName','skillFormDescription','skillFormPromptTemplate','skillFormToolChain','skillFormTags'].forEach(id => {
                const el = document.getElementById(id); if (el) el.value = '';
            });
            const cat = document.getElementById('skillFormCategory'); if (cat) cat.value = 'general';
        }
    }
    closeCreateSkillModal() { if (this.skillCreateModal) this.skillCreateModal.style.display = 'none'; }

    async createSkill() {
        const name = document.getElementById('skillFormName')?.value.trim();
        if (!name) { this.showNotification('请输入技能名称', 'warning'); return; }
        const payload = {
            name,
            description: document.getElementById('skillFormDescription')?.value.trim() || '',
            category: document.getElementById('skillFormCategory')?.value || 'general',
            promptTemplate: document.getElementById('skillFormPromptTemplate')?.value.trim() || null,
            toolChainDescription: document.getElementById('skillFormToolChain')?.value.trim() || null,
            tags: document.getElementById('skillFormTags')?.value.trim()
                ? document.getElementById('skillFormTags').value.split(',').map(t => t.trim()).filter(Boolean)
                : []
        };
        try {
            const response = await fetch(`${this.apiBaseUrl}/skills`, {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await response.json();
            if (data.code === 200) {
                this.showNotification(`技能 "${data.data.name}" 创建成功`, 'success');
                this.closeCreateSkillModal();
                this.loadSkills();
            }
        } catch (e) {
            this.showNotification('创建失败: ' + e.message, 'error');
        }
    }

    async openSkillDetail(skillId) {
        try {
            const response = await fetch(`${this.apiBaseUrl}/skills/${encodeURIComponent(skillId)}`);
            const data = await response.json();
            const skill = data.data;
            if (!skill) throw new Error('技能不存在');

            const categoryLabels = { troubleshooting:'故障排查', monitoring:'监控', deployment:'部署', networking:'网络', security:'安全', database:'数据库', general:'通用' };
            const sourceLabels = { precipitated:'AI沉淀', searched:'GitHub', manual:'手动创建' };

            const nameEl = document.getElementById('skillDetailName');
            if (nameEl) nameEl.textContent = skill.name;

            const body = document.getElementById('skillDetailBody');
            if (body) {
                body.innerHTML = `
                    <div class="skill-detail-section"><h4>描述</h4><p>${this.escapeHtml(skill.description || '暂无描述')}</p></div>
                    <div class="skill-detail-section"><h4>分类 / 来源</h4>
                        <div class="skill-card-badges">
                            <span class="skill-badge skill-badge-category">${categoryLabels[skill.category]||skill.category}</span>
                            <span class="skill-badge skill-badge-source-${skill.source_type}">${sourceLabels[skill.source_type]||skill.source_type}</span>
                        </div>
                    </div>
                    ${skill.prompt_template ? `<div class="skill-detail-section"><h4>提示模板</h4><pre>${this.escapeHtml(skill.prompt_template)}</pre></div>` : ''}
                    ${skill.tool_chain_description ? `<div class="skill-detail-section"><h4>工具链</h4><p>${this.escapeHtml(skill.tool_chain_description)}</p></div>` : ''}
                    ${skill.tags && skill.tags.length > 0 ? `<div class="skill-detail-section"><h4>标签</h4>${skill.tags.map(t=>`<span class="skill-detail-tag">${this.escapeHtml(t)}</span>`).join('')}</div>` : ''}
                    <div class="skill-detail-section"><h4>统计</h4><p>使用次数: ${skill.usage_count||0} / 成功率: ${Math.round((skill.success_rate||0)*100)}%</p></div>
                    <button class="skill-precipitate-btn" style="background:#ea4335;margin-top:16px;" onclick="window._app.deleteSkill('${this.escapeHtml(skill.id)}');window._app.closeSkillDetailModal();">删除此技能</button>
                `;
            }
            if (this.skillDetailModal) this.skillDetailModal.style.display = 'flex';
        } catch (e) { console.error('获取技能详情失败:', e); }
    }
    closeSkillDetailModal() { if (this.skillDetailModal) this.skillDetailModal.style.display = 'none'; }

    async toggleSkill(skillId, enabled) {
        try {
            await fetch(`${this.apiBaseUrl}/skills/${encodeURIComponent(skillId)}/toggle?enabled=${enabled}`, { method: 'PATCH' });
            this.showNotification(enabled ? '技能已启用' : '技能已禁用', 'success');
        } catch (e) { this.showNotification('操作失败: ' + e.message, 'error'); this.loadSkills(); }
    }

    async deleteSkill(skillId) {
        if (!(await this.showConfirm('确定要删除此技能吗？此操作不可恢复。'))) return;
        try {
            await fetch(`${this.apiBaseUrl}/skills/${encodeURIComponent(skillId)}`, { method: 'DELETE' });
            this.showNotification('技能已删除', 'success');
            this.loadSkills();
        } catch (e) { this.showNotification('删除失败: ' + e.message, 'error'); }
    }

    async precipitateSkill(sessionId) {
        try {
            this.showNotification('正在从会话中提取技能...', 'info');
            const response = await fetch(`${this.apiBaseUrl}/skills/precipitate`, {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId })
            });
            const data = await response.json();
            if (data.code === 200) {
                this.showNotification(`技能 "${data.data.name}" 沉淀成功`, 'success');
                this.loadSkills();
            }
        } catch (e) { this.showNotification('沉淀失败: ' + e.message, 'error'); }
    }

    addSkillPrecipitateButton(messageElement, sessionId) {
        if (!messageElement || !sessionId) return;
        const contentEl = messageElement.querySelector('.message-content');
        if (!contentEl) return;
        const btnContainer = document.createElement('div');
        btnContainer.className = 'skill-precipitate-container';
        btnContainer.innerHTML = `<button class="skill-precipitate-btn" onclick="window._app.precipitateSkill('${this.escapeHtml(sessionId)}')">➕ 沉淀为技能</button>`;
        contentEl.appendChild(btnContainer);
    }

    // ========== 确认弹窗 ==========

    showConfirm(message) {
        return new Promise((resolve) => {
            if (this.confirmModalMessage) this.confirmModalMessage.textContent = message;
            if (this.confirmModal) this.confirmModal.style.display = 'flex';
            const onOk = () => { cleanup(); resolve(true); };
            const onCancel = () => { cleanup(); resolve(false); };
            const cleanup = () => {
                if (this.confirmModal) this.confirmModal.style.display = 'none';
                if (this.confirmModalOK) this.confirmModalOK.removeEventListener('click', onOk);
                if (this.confirmModalCancel) this.confirmModalCancel.removeEventListener('click', onCancel);
            };
            if (this.confirmModalOK) this.confirmModalOK.addEventListener('click', onOk);
            if (this.confirmModalCancel) this.confirmModalCancel.addEventListener('click', onCancel);
        });
    }

    // ========== 主题切换 ==========

    initTheme() {
        const saved = localStorage.getItem('theme');
        const isDark = saved === 'dark' || (!saved && window.matchMedia('(prefers-color-scheme: dark)').matches);
        document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
        if (this.themeToggleCheckbox) {
            this.themeToggleCheckbox.checked = isDark;
        }
    }

    toggleTheme() {
        const isDark = this.themeToggleCheckbox?.checked;
        document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
        localStorage.setItem('theme', isDark ? 'dark' : 'light');
    }
}

// 添加CSS动画
const style = document.createElement('style');
style.textContent = `
    @keyframes slideIn {
        from {
            transform: translateX(100%);
            opacity: 0;
        }
        to {
            transform: translateX(0);
            opacity: 1;
        }
    }
    
    @keyframes slideOut {
        from {
            transform: translateX(0);
            opacity: 1;
        }
        to {
            transform: translateX(100%);
            opacity: 0;
        }
    }
`;
document.head.appendChild(style);

// 初始化应用
document.addEventListener('DOMContentLoaded', () => {
    new SuperBizAgentApp();
});
