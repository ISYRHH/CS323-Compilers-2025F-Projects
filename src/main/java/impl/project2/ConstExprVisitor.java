package impl.project2;

import generated.Splc.SplcBaseVisitor;
import generated.Splc.SplcParser;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

public class ConstExprVisitor extends SplcBaseVisitor<Integer> {

    /**
     * 处理带运算符的表达式（二元运算、一元负号）
     * 对应语法中的 expression
     */
    @Override
    public Integer visitExpression(SplcParser.ExpressionContext ctx) {
        // 数字常量
        if (ctx.Number() != null) {
            TerminalNode numberNode = ctx.Number();
            return Integer.parseInt(numberNode.getText());
        }
        // 变量不是常量
        else if (ctx.Identifier() != null) {
            return null;
        }
        // 括号表达式
        else if (ctx.LPAREN() != null && ctx.RPAREN() != null && !ctx.expression().isEmpty()) {
            // 取第一个子表达式并递归访问
            return visit(ctx.expression(0));
        }
        // 二元运算
        if (ctx.getChildCount() == 3) {
            SplcParser.ExpressionContext leftExpr = (SplcParser.ExpressionContext) ctx.getChild(0);
            TerminalNode opNode = (TerminalNode) ctx.getChild(1);
            SplcParser.ExpressionContext rightExpr = (SplcParser.ExpressionContext) ctx.getChild(2);
            Integer leftVal = visit(leftExpr);
            Integer rightVal = visit(rightExpr);
            if (leftVal == null || rightVal == null) {
                return null;
            }
            String op = opNode.getText();
            return switch (op) {
                case "+" -> leftVal + rightVal;
                case "-" -> leftVal - rightVal;
                case "*" -> leftVal * rightVal;
                case "/" -> leftVal / rightVal;
                case "%" -> leftVal % rightVal;
                default -> null;
            };
        }
        // 一元负号
        else if (ctx.getChildCount() == 2) {
            TerminalNode opNode = (TerminalNode) ctx.getChild(0);
            SplcParser.ExpressionContext expr = (SplcParser.ExpressionContext) ctx.getChild(1);
            if (opNode.getSymbol().getType() == SplcParser.MINUS) {
                Integer val = visit(expr);
                return val != null ? -val : null;
            }
        }
        // 单节点表达式
        else if (ctx.getChildCount() == 1) {
        }
        // 其他情况
        return null;
    }
}
