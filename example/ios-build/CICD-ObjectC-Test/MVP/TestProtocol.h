//
//  TestProtocol.h
//  CICD-ObjectC-Test
//
//  Created by Test on 2020/8/17.
//  Copyright © 2020 Test. All rights reserved.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@protocol TestProtocol <NSObject>

@required
/*!
 *  @method protocolShowAlertMsg:
 *
 *  @param message      展示的内容
 *
 *  @discussion             展示 message
 */
- (void)protocolShowAlertMsg:(NSString *)message;
/*!
 *  @method protocolClickCloseButton
 *
 *  @discussion             在Alert弹层中点击了关闭按钮
 */
- (void)protocolClickCloseButton;
/*!
 *  @method protocolClickConfirmButton
 *
 *  @discussion             在Alert弹层中点击了确定按钮
 */
- (void)protocolClickConfirmButton;

@end

NS_ASSUME_NONNULL_END
