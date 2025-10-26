package impl.project2;

import generated.Splc.SplcBaseVisitor;
import generated.Splc.SplcParser;
import org.antlr.v4.runtime.tree.TerminalNode;

public class ConstExprVisitor extends SplcBaseVisitor<Integer> {

    /**
     * 更稳健地处理 expression 节点，识别并求值 constexpr（整数常量、括号、前缀一元 +/-, 可选前缀 ++/--，以及二元 + - * / %）。
     * 对于其它形式（变量、函数调用、数组访问、后缀自增等）返回 null 表示非 constexpr。
     */
    @Override
    public Integer visitExpression(SplcParser.ExpressionContext ctx) {
        // 纯数字常量
        if (ctx.Number() != null) {
            return Integer.parseInt(ctx.Number().getText());
        }

        // 标识符、函数调用、数组访问 等含变量或非纯算术的形式不是 constexpr
        if (ctx.Identifier() != null) {
            return null;
        }
        if (ctx.Char() != null) {
            // 按项目定义，只有整数常量被视为 constexpr；对字符常量返回 null
            return null;
        }

        // 括号：LPAREN expression RPAREN
        if (ctx.LPAREN() != null && ctx.expression().size() == 1) {
            return visit(ctx.expression(0));
        }

        // 处理含子表达式的情况
        int exprCount = ctx.expression().size();

        // 前缀或后缀一元运算（只有前缀我们考虑求值，后缀视为非 constexpr）
        if (exprCount == 1) {
            // 如果 child0 是 TerminalNode，说明是前缀： op expression
            if (ctx.getChild(0) instanceof TerminalNode) {
                TerminalNode opNode = (TerminalNode) ctx.getChild(0);
                Integer val = visit(ctx.expression(0));
                if (val == null) return null;
                int t = opNode.getSymbol().getType();
                switch (t) {
                    case SplcParser.PLUS:
                        return val;
                    case SplcParser.MINUS:
                        return -val;
                    case SplcParser.INC:
                        return val + 1;
                    case SplcParser.DEC:
                        return val - 1;
                    default:
                        // 其他前缀（NOT, AMP, STAR 等）不属于 constexpr 的允许项
                        return null;
                }
            } else {
                // 否则很可能是后缀形式（expression INC/DEC/调用等）——不是 constexpr
                return null;
            }
        }

        // 二元运算：expression op expression
        if (exprCount == 2) {
            Integer left = visit(ctx.expression(0));
            Integer right = visit(ctx.expression(1));
            if (left == null || right == null) return null;
            // 获取中间操作符（child 索引通常为 1）
            if (ctx.getChildCount() >= 2 && ctx.getChild(1) instanceof TerminalNode) {
                String op = ctx.getChild(1).getText();
                try {
                    return switch (op) {
                        case "+" -> left + right;
                        case "-" -> left - right;
                        case "*" -> left * right;
                        case "/" -> left / right;
                        case "%" -> left % right;
                        default -> null;
                    };
                } catch (ArithmeticException ae) {
                    // 比如除以 0，视为非 constexpr（或可根据需要抛出）
                    return null;
                }
            }
        }

        // 其他任意形式（赋值、逻辑运算、比较、函数调用、数组访问、结构体访问等）都不是 constexpr
        return null;
    }
}
