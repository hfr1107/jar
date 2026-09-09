"""影视TV chaquo 模块里 base/spider.py 的等价物（仅用于沙盒加载测试）"""


class Spider:
    def getName(self):
        return ""

    def init(self, extend=""):
        pass

    def homeContent(self, filter):
        return {}

    def homeVideoContent(self):
        return {}

    def categoryContent(self, tid, pg, filter, extend):
        return {}

    def detailContent(self, ids):
        return {}

    def searchContent(self, key, quick, pg="1"):
        return {}

    def playerContent(self, flag, id, vipFlags):
        return {}

    def liveContent(self, url):
        return {}

    def isVideoFormat(self, url):
        return False

    def manualVideoCheck(self):
        return False

    def destroy(self):
        pass

    def localProxy(self, param):
        return None
